package dev.ryanbuu.infrapilot.ups;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class UpsClientTest {
  HttpServer server;
  UpsClient client;
  ObjectMapper mapper = new ObjectMapper();
  AtomicInteger logins = new AtomicInteger(), writes = new AtomicInteger();

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    UpsProperties p = new UpsProperties();
    p.setEnabled(true);
    p.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
    p.setUsername("test");
    p.setPassword("test-secret");
    var app = new InfraPilotProperties();
    app.setMode("live");
    client = new UpsClient(p, app, mapper, new UpsDemo(mapper));
    server.createContext(
        "/auth/api/login",
        q -> {
          logins.incrementAndGet();
          q.getResponseHeaders().add("Set-Cookie", "session=test-cookie; Path=/; HttpOnly");
          byte[] b = "{\"success\":true}".getBytes();
          q.sendResponseHeaders(200, b.length);
          q.getResponseBody().write(b);
          q.close();
        });
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void reusesServerOnlySessionAndDoesNotReturnCookies() {
    server.createContext(
        "/api/data/all",
        q -> {
          assertThat(q.getRequestHeaders().getFirst("Cookie")).contains("test-cookie");
          byte[] b = "{\"success\":true,\"data\":{\"battery_charge\":80}}".getBytes();
          q.sendResponseHeaders(200, b.length);
          q.getResponseBody().write(b);
          q.close();
        });
    assertThat(
            client
                .call("GET", "/api/data/all?target_id=1", null, false)
                .data()
                .path("data")
                .path("battery_charge")
                .asInt())
        .isEqualTo(80);
    client.call("GET", "/api/data/all?target_id=2", null, false);
    assertThat(logins.get()).isEqualTo(1);
  }

  @Test
  void doesNotFollowRedirectsOrReplayFailedWrites() {
    server.createContext(
        "/api/upscmd/execute",
        q -> {
          writes.incrementAndGet();
          q.getResponseHeaders().set("Location", "http://127.0.0.1:1/stolen");
          q.sendResponseHeaders(302, -1);
          q.close();
        });
    assertThatThrownBy(
            () ->
                client.call(
                    "POST",
                    "/api/upscmd/execute",
                    mapper.createObjectNode().put("command", "test"),
                    false))
        .isInstanceOf(ApiException.class);
    assertThat(writes.get()).isEqualTo(1);
  }

  @Test
  void rejectsSuccessfulHttpWithFailedApplicationResult() {
    server.createContext(
        "/api/upscmd/execute",
        q -> {
          writes.incrementAndGet();
          byte[] b = "{\"success\":false,\"error\":\"permission denied\"}".getBytes();
          q.sendResponseHeaders(200, b.length);
          q.getResponseBody().write(b);
          q.close();
        });
    assertThatThrownBy(
            () -> client.call("POST", "/api/upscmd/execute", mapper.createObjectNode(), false))
        .hasMessageContaining("拒绝");
    assertThat(writes.get()).isEqualTo(1);
  }

  @Test
  void scopesWritesInBothUrlAndBodyWithoutAcceptingUserOverrides() throws Exception {
    var catalog = new UpsCatalog(mapper);
    var op = catalog.find("POST", "/api/multi-nut/renamer/mappings").orElseThrow();
    var call =
        catalog.resolve(
            op.path("id").asText(), mapper.readTree("{\"targetId\":9,\"body\":{\"mappings\":{}}}"));
    assertThat(call.path()).endsWith("target_id=9");
    assertThat(call.body().path("target_id").asInt()).isEqualTo(9);
  }
}
