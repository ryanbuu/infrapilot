package dev.ryanbuu.infrapilot.bind;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public class BindClient {
  private final BindProperties properties;
  private final ObjectMapper mapper;
  private final boolean demo;
  private final BindDemo sample;

  public BindClient(BindProperties properties, InfraPilotProperties app, ObjectMapper mapper) {
    this.properties = properties;
    this.mapper = mapper;
    this.demo = app.getMode().equals("demo");
    this.sample = new BindDemo(mapper);
  }

  public synchronized ObjectNode call(BindProperties.Node node, ObjectNode request) {
    if (demo) return sample.call(node, request);
    Process process = null;
    try {
      if (!node.id().matches("[a-zA-Z0-9_-]{1,64}")
          || !node.host().matches("[a-zA-Z0-9.:_-]{1,253}")
          || !node.user().matches("[a-z_][a-z0-9_-]{0,31}")
          || node.port() < 1
          || node.port() > 65535) throw new IllegalStateException();
      byte[] payload = mapper.writeValueAsBytes(request);
      if (payload.length > 4194304) throw new ApiException(BAD_REQUEST, "请求过大");
      process =
          new ProcessBuilder(
                  "ssh",
                  "-F",
                  "/dev/null",
                  "-T",
                  "-a",
                  "-x",
                  "-o",
                  "BatchMode=yes",
                  "-o",
                  "IdentitiesOnly=yes",
                  "-o",
                  "StrictHostKeyChecking=yes",
                  "-o",
                  "ConnectTimeout=5",
                  "-o",
                  "ServerAliveInterval=10",
                  "-o",
                  "ServerAliveCountMax=2",
                  "-o",
                  "GlobalKnownHostsFile=/dev/null",
                  "-o",
                  "UserKnownHostsFile=" + properties.getKnownHostsFile(),
                  "-i",
                  properties.getPrivateKeyFile(),
                  "-p",
                  Integer.toString(node.port()),
                  node.user() + "@" + node.host())
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      Process running = process;
      CompletableFuture<byte[]> output =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  byte[] bytes = running.getInputStream().readNBytes(8388609);
                  if (bytes.length > 8388608) {
                    running.destroyForcibly();
                    throw new IllegalStateException();
                  }
                  return bytes;
                } catch (Exception e) {
                  throw new CompletionException(e);
                }
              });
      try (var input = process.getOutputStream()) {
        input.write(payload);
      }
      if (!process.waitFor(60, TimeUnit.SECONDS)) throw new TimeoutException();
      if (process.exitValue() != 0) throw new IllegalStateException();
      JsonNode response =
          mapper.readTree(new String(output.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8));
      if (!response.path("ok").asBoolean()) {
        int code = response.path("code").asInt(502);
        throw new ApiException(
            code == 400 ? BAD_REQUEST : code == 409 ? CONFLICT : BAD_GATEWAY,
            node.label() + "：" + response.path("message").asText("节点操作失败"));
      }
      if (!(response.get("result") instanceof ObjectNode result)) throw new IllegalStateException();
      return result;
    } catch (ApiException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(BAD_GATEWAY, node.label() + "：操作被中断，需核实事务");
    } catch (Exception e) {
      throw new ApiException(BAD_GATEWAY, node.label() + "：无法确认节点结果，请检查受限 SSH 连接");
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
    }
  }
}
