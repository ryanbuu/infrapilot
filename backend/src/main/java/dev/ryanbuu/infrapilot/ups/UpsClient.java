package dev.ryanbuu.infrapilot.ups;

import com.fasterxml.jackson.databind.*;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class UpsClient {
  public record Reply(JsonNode data, byte[] binary, String contentType) {}

  private final UpsProperties p;
  private final InfraPilotProperties app;
  private final ObjectMapper mapper;
  private final UpsDemo demo;
  private final CookieManager cookies =
      new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
  private final HttpClient http;
  private boolean authenticated;

  public UpsClient(UpsProperties p, InfraPilotProperties app, ObjectMapper mapper, UpsDemo demo) {
    this.p = p;
    this.app = app;
    this.mapper = mapper;
    this.demo = demo;
    http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public boolean demo() {
    return app.getMode().equals("demo");
  }

  public boolean enabled() {
    return demo() || p.isEnabled();
  }

  private URI uri(String path) {
    URI base = URI.create(p.getUrl());
    if (!java.util.Set.of("http", "https").contains(base.getScheme())
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null
        || !java.util.Set.of("", "/").contains(base.getPath()))
      throw new IllegalStateException("UPS URL must be an HTTP origin");
    if (!path.startsWith("/") || path.startsWith("//")) throw UpsCatalog.bad("无效的 UPS 路径");
    return URI.create(p.getUrl().replaceAll("/$", "") + path);
  }

  private HttpResponse<InputStream> send(String method, String path, JsonNode body)
      throws Exception {
    var b =
        HttpRequest.newBuilder(uri(path))
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json");
    if (method.equals("GET")) b.GET();
    else
      b.header("Content-Type", "application/json")
          .method(
              method,
              HttpRequest.BodyPublishers.ofString(
                  body == null ? "{}" : mapper.writeValueAsString(body)));
    return http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
  }

  private byte[] bytes(HttpResponse<InputStream> r) throws IOException {
    try (var in = r.body()) {
      byte[] b = in.readNBytes(32 * 1024 * 1024 + 1);
      if (b.length > 32 * 1024 * 1024) throw UpsCatalog.bad("UPS 响应超过 32 MB，请缩小查询范围");
      return b;
    }
  }

  private void login() throws Exception {
    if (p.getUsername().isBlank() || p.getPassword().isBlank())
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "UPS 连接凭据未配置");
    cookies.getCookieStore().removeAll();
    var body =
        mapper.createObjectNode().put("username", p.getUsername()).put("password", p.getPassword());
    var response = send("POST", "/auth/api/login", body);
    byte[] raw = bytes(response);
    if (response.statusCode() != 200 || cookies.getCookieStore().getCookies().isEmpty())
      throw new ApiException(HttpStatus.BAD_GATEWAY, "Nutify 登录失败，请检查服务端连接凭据");
    JsonNode result = mapper.readTree(raw);
    if (result.path("success").isBoolean() && !result.path("success").asBoolean())
      throw new ApiException(HttpStatus.BAD_GATEWAY, "Nutify 登录失败");
    authenticated = true;
  }

  public synchronized Reply call(String method, String path, JsonNode body, boolean binary) {
    if (demo()) return demo.call(method, path, body, binary);
    if (!p.isEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "UPS 集成尚未启用");
    try {
      if (!authenticated) login();
      var r = send(method, path, body);
      // Re-authenticate only after an explicit authentication rejection. Never retry ambiguous
      // writes.
      if (r.statusCode() == 401) {
        bytes(r);
        authenticated = false;
        login();
        r = send(method, path, body);
      }
      byte[] raw = bytes(r);
      String type = r.headers().firstValue("Content-Type").orElse("application/octet-stream");
      if (r.statusCode() < 200 || r.statusCode() >= 300)
        throw new ApiException(HttpStatus.BAD_GATEWAY, "Nutify 请求失败（HTTP " + r.statusCode() + "）");
      if (binary && !type.contains("json")) return new Reply(null, raw, type);
      JsonNode data = mapper.readTree(raw);
      if (data.has("success") && !data.path("success").asBoolean()
          || java.util.Set.of("error", "failed").contains(data.path("status").asText()))
        throw new ApiException(
            HttpStatus.BAD_GATEWAY,
            "Nutify 拒绝操作："
                + safeMessage(data.path("error").asText(data.path("message").asText("请查看服务端日志"))));
      return new Reply(data, null, "application/json");
    } catch (ApiException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(HttpStatus.BAD_GATEWAY, "UPS 请求中断，写操作结果需刷新核对");
    } catch (Exception e) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "无法完成 UPS 请求；写操作不会自动重试，请刷新核对结果");
    }
  }

  private String safeMessage(String s) {
    if (s.contains(p.getPassword()) && !p.getPassword().isBlank())
      s = s.replace(p.getPassword(), "[redacted]");
    if (s.matches("(?is).*(password|secret|token|credential|config).*")) return "请检查参数和服务端日志";
    return s.length() > 240 ? s.substring(0, 240) : s;
  }
}
