package dev.ryanbuu.infrapilot.gateway;

import com.fasterxml.jackson.databind.*;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class GatewayClient {
  public record Reply(int status, JsonNode data) {}

  private final GatewayProperties p;
  private final InfraPilotProperties settings;
  private final ObjectMapper mapper;
  private final GatewayDemo demo;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private volatile String version = "";

  public GatewayClient(GatewayProperties p, InfraPilotProperties settings, ObjectMapper mapper) {
    this.p = p;
    this.settings = settings;
    this.mapper = mapper;
    this.demo = new GatewayDemo(mapper);
  }

  public boolean demo() {
    return "demo".equals(settings.getMode());
  }

  public boolean enabled() {
    return demo() || p.isEnabled();
  }

  public String version() {
    return demo() ? "3.13.0 · Demo" : version;
  }

  public String endpoint() {
    return demo() ? "演示实例" : p.getUrl();
  }

  public Reply request(String method, String path, JsonNode body) {
    if (!enabled())
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Application Gateway 尚未连接");
    if (demo()) return demo.request(method, path, body);
    try {
      URI base = URI.create(p.getUrl());
      if (!java.util.Set.of("http", "https").contains(base.getScheme())
          || base.getHost() == null
          || base.getUserInfo() != null
          || base.getQuery() != null
          || base.getFragment() != null
          || p.getApiKey().isBlank()
          || !path.startsWith("/")
          || path.contains("..")
          || path.contains("://")) throw new IllegalArgumentException();
      String prefix = base.toString().replaceAll("/+$", "");
      if (!prefix.endsWith("/apisix/admin")) prefix += "/apisix/admin";
      var request =
          HttpRequest.newBuilder(URI.create(prefix + path))
              .timeout(Duration.ofSeconds(12))
              .header("X-API-KEY", p.getApiKey())
              .header("Accept", "application/json");
      if (body != null) request.header("Content-Type", "application/json");
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body.toString()));
      var response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      response
          .headers()
          .firstValue("Server")
          .filter(s -> s.startsWith("APISIX/"))
          .ifPresent(s -> version = s.substring(7));
      try (InputStream input = response.body()) {
        byte[] bytes = input.readNBytes(8_000_001);
        if (bytes.length > 8_000_000) throw new IllegalStateException();
        JsonNode data;
        try {
          data = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
          if (response.statusCode() >= 200 && response.statusCode() < 300)
            throw new IllegalStateException();
          data = mapper.createObjectNode();
        }
        if (data == null && method.equals("GET") && response.statusCode() == 200)
          throw new IllegalStateException();
        return new Reply(response.statusCode(), data == null ? mapper.createObjectNode() : data);
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new ApiException(HttpStatus.BAD_GATEWAY, "网关管理接口响应未确认，请刷新后核对资源状态");
    }
  }
}
