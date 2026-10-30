package dev.ryanbuu.infrapilot.forwarding;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public class ForwardingClient {
  private final ForwardingProperties properties;
  private final ObjectMapper mapper;
  private final boolean demo;
  private final Map<String, ObjectNode> demoStates = new HashMap<>();
  private final Map<String, ObjectNode> demoTransactions = new HashMap<>();

  public ForwardingClient(
      ForwardingProperties properties, InfraPilotProperties app, ObjectMapper mapper) {
    this.properties = properties;
    this.mapper = mapper;
    this.demo = app.getMode().equals("demo");
  }

  public synchronized ObjectNode call(ForwardingProperties.Node node, ObjectNode request) {
    if (demo) return demo(node, request);
    Process process = null;
    try {
      if (!node.id().matches("[a-zA-Z0-9_-]{1,64}")
          || !node.host().matches("[a-zA-Z0-9.:_-]{1,253}")
          || !node.user().matches("[a-z_][a-z0-9_-]{0,31}")
          || node.port() < 1
          || node.port() > 65535) throw new IllegalStateException();
      byte[] payload = mapper.writeValueAsBytes(request);
      if (payload.length > 262144) throw new ApiException(BAD_REQUEST, "请求过大");
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
                  byte[] bytes = running.getInputStream().readNBytes(1048577);
                  if (bytes.length > 1048576) {
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
      if (!process.waitFor(45, TimeUnit.SECONDS)) throw new TimeoutException();
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

  private ObjectNode demo(ForwardingProperties.Node node, ObjectNode request) {
    ObjectNode state =
        demoStates.computeIfAbsent(
            node.id(),
            id -> {
              ObjectNode s = mapper.createObjectNode();
              s.put("node", id);
              s.put("revision", UUID.randomUUID().toString());
              s.set(
                  "rules",
                  mapper.valueToTree(
                      List.of(
                          new ForwardingRules.Rule(
                              "example.net",
                              "DOT",
                              "only",
                              853,
                              List.of("192.0.2.53", "192.0.2.54"),
                              "resolver.example.net",
                              ""))));
              s.put("drift", false);
              s.putNull("pending");
              s.set("protected", mapper.valueToTree(List.of("intra.example.com")));
              return s;
            });
    String op = request.path("operation").asText(), tx = request.path("transaction").asText();
    if (op.equals("read")) return state.deepCopy();
    if (Set.of("validate", "prepare").contains(op)) {
      if (!request.path("revision").asText().equals(state.path("revision").asText()))
        throw new ApiException(CONFLICT, "配置版本已变化，请刷新后重试");
      List<ForwardingRules.Rule> rules =
          ForwardingRules.validate(
              mapper.convertValue(request.get("rules"), new TypeReference<>() {}));
      ForwardingRules.protect(rules, List.of("intra.example.com", "2.0.192.in-addr.arpa"));
      if (state.path("pending").isObject()) throw new ApiException(CONFLICT, "节点存在待结束事务");
      if (op.equals("validate")) return mapper.createObjectNode().put("valid", true);
      ObjectNode t = mapper.createObjectNode();
      t.set("before", state.deepCopy());
      t.set("rules", mapper.valueToTree(rules));
      t.put("status", "prepared");
      demoTransactions.put(node.id() + tx, t);
      state.set("pending", mapper.createObjectNode().put("id", tx).put("status", "prepared"));
      return mapper.createObjectNode().put("status", "prepared");
    }
    ObjectNode t = demoTransactions.get(node.id() + tx);
    if (t == null && op.equals("abort"))
      return mapper.createObjectNode().put("status", "not-prepared");
    if (t == null) throw new ApiException(CONFLICT, "事务不存在");
    switch (op) {
      case "commit" -> {
        state.set("rules", t.get("rules").deepCopy());
        state.put("revision", UUID.randomUUID().toString());
        t.put("status", "applied");
      }
      case "abort" -> {
        state = ((ObjectNode) t.get("before")).deepCopy();
        demoStates.put(node.id(), state);
        t.put("status", "rolled-back");
      }
      case "finalize" -> {
        state.putNull("pending");
        t.put("status", "complete");
      }
      default -> throw new ApiException(BAD_REQUEST, "不支持的操作");
    }
    return mapper.createObjectNode().put("status", t.path("status").asText());
  }
}
