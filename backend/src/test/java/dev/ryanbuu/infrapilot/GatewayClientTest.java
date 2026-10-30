package dev.ryanbuu.infrapilot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.audit.AuditService;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties;
import dev.ryanbuu.infrapilot.gateway.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class GatewayClientTest {
  final ObjectMapper mapper = new ObjectMapper();
  HttpServer server;
  GatewayProperties p;
  GatewayClient client;

  @BeforeEach
  void start() throws Exception {
    p = new GatewayProperties();
    p.setEnabled(true);
    p.setApiKey("private-test-admin-key");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    p.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
    var settings = new InfraPilotProperties();
    settings.setMode("live");
    client = new GatewayClient(p, settings, mapper);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void customMetadataValidationDoesNotCallTheBrokenGenericValidator() {
    GatewayClient mock = mock(GatewayClient.class);
    when(mock.request(eq("GET"), eq("/plugin_metadata/test-plugin"), isNull()))
        .thenReturn(new GatewayClient.Reply(404, mapper.createObjectNode()));
    GatewayService service =
        new GatewayService(
            mock, p, new GatewayBackups(p, mapper), mapper, mock(AuditService.class));
    var preview =
        service.preview(
            "plugin_metadata",
            new GatewayService.Mutation(
                "CREATE", "test-plugin", null, "absent", mapper.createObjectNode()));
    assertThat(preview.get("validation").toString()).contains("专属校验");
    verify(mock, never()).request(eq("POST"), anyString(), any());
  }

  @Test
  void pinsRequestsToTheConfiguredAdminEndpointAndDoesNotFollowRedirects() {
    AtomicReference<String> key = new AtomicReference<>(), path = new AtomicReference<>();
    server.createContext(
        "/apisix/admin/routes",
        q -> {
          key.set(q.getRequestHeaders().getFirst("X-API-KEY"));
          path.set(q.getRequestURI().getPath());
          q.getResponseHeaders().add("Location", "http://127.0.0.1:1/credential-leak");
          q.sendResponseHeaders(302, -1);
          q.close();
        });
    assertThat(client.request("GET", "/routes", null).status()).isEqualTo(302);
    assertThat(key.get()).isEqualTo("private-test-admin-key");
    assertThat(path.get()).isEqualTo("/apisix/admin/routes");
    assertThatThrownBy(() -> client.request("GET", "/../config", null))
        .isInstanceOf(ApiException.class)
        .hasMessageNotContaining(p.getApiKey());
  }

  @Test
  void refusesWritesWhenBackupCannotBePersisted() throws Exception {
    GatewayClient mock = mock(GatewayClient.class);
    when(mock.request(eq("GET"), eq("/upstreams/new"), isNull()))
        .thenReturn(new GatewayClient.Reply(404, mapper.createObjectNode()));
    when(mock.request(eq("POST"), eq("/schema/validate/upstreams"), any()))
        .thenReturn(new GatewayClient.Reply(200, mapper.createObjectNode()));
    Path file = Files.createTempFile("gateway-not-a-directory-", ".tmp");
    p.setBackupDirectory(file.toString());
    AuditService audit = mock(AuditService.class);
    GatewayService service =
        new GatewayService(mock, p, new GatewayBackups(p, mapper), mapper, audit);
    assertThatThrownBy(
            () ->
                service.change(
                    "upstreams",
                    new GatewayService.Mutation(
                        "CREATE",
                        "new",
                        null,
                        "absent",
                        mapper.createObjectNode().put("type", "roundrobin")),
                    "admin"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("未执行");
    verify(mock, never()).request(eq("PUT"), anyString(), any());
    verifyNoInteractions(audit);
    Files.delete(file);
  }

  @Test
  void maskedValuesRestoreOriginalArraysAndSchemaErrorsCannotEchoCredentials() throws Exception {
    JsonNode raw =
        mapper.readTree(
            "{\"key\":\"private-key\",\"plugins\":{\"key-auth\":{\"key\":\"auth-secret\"}},\"tls\":{\"client_key\":\"tls-secret\"},\"nodes\":{\"192.0.2.1:80\":1}}");
    JsonNode masked = GatewaySecrets.redact(raw);
    assertThat(masked.toString()).doesNotContain("private-key", "auth-secret", "tls-secret");
    assertThat(GatewaySecrets.resolve(masked, raw)).isEqualTo(raw);
    assertThat(
            GatewaySecrets.safeError("bad auth-secret private-test-admin-key", raw, p.getApiKey()))
        .doesNotContain("auth-secret", p.getApiKey());
  }

  @Test
  void certificateEditPreservesTheStoredEncryptedPrivateKey() throws Exception {
    GatewayClient mock = mock(GatewayClient.class);
    ObjectNode original =
        mapper
            .createObjectNode()
            .put("id", "tls")
            .put("cert", "certificate")
            .put("key", "encrypted-private-key")
            .put("status", 1);
    original.putArray("snis").add("example.test");
    AtomicReference<JsonNode> stored = new AtomicReference<>(original);
    when(mock.request(eq("GET"), eq("/ssls"), isNull()))
        .thenAnswer(
            i -> {
              ObjectNode response = mapper.createObjectNode();
              response
                  .putArray("list")
                  .addObject()
                  .put("key", "/apisix/ssls/tls")
                  .put("modifiedIndex", 1)
                  .set("value", stored.get());
              return new GatewayClient.Reply(200, response);
            });
    when(mock.request(eq("POST"), eq("/schema/validate/ssls"), any()))
        .thenReturn(new GatewayClient.Reply(200, mapper.createObjectNode()));
    when(mock.request(eq("PUT"), eq("/ssls/tls"), any()))
        .thenAnswer(
            i -> {
              stored.set(i.getArgument(2));
              return new GatewayClient.Reply(200, mapper.createObjectNode());
            });
    p.setBackupDirectory(Files.createTempDirectory("gateway-cert-").toString());
    AuditService audit = mock(AuditService.class);
    when(audit.beginObject(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(1L);
    GatewayService service =
        new GatewayService(mock, p, new GatewayBackups(p, mapper), mapper, audit);
    var item = service.resources("ssls", null).items().getFirst();
    ObjectNode next = ((ObjectNode) item.value()).deepCopy().put("status", 0);
    assertThat(
            service
                .change(
                    "ssls",
                    new GatewayService.Mutation("UPDATE", "tls", null, item.revision(), next),
                    "admin")
                .get("verified"))
        .isEqualTo(true);
    assertThat(stored.get().path("key").asText()).isEqualTo("encrypted-private-key");
    verify(mock, never()).request(eq("GET"), eq("/ssls/tls"), any());
  }

  @Test
  void uncertainWriteIsRecordedAsPendingInsteadOfSuccess() throws Exception {
    GatewayClient mock = mock(GatewayClient.class);
    when(mock.request(eq("GET"), eq("/upstreams/new"), isNull()))
        .thenReturn(new GatewayClient.Reply(404, mapper.createObjectNode()));
    when(mock.request(eq("POST"), eq("/schema/validate/upstreams"), any()))
        .thenReturn(new GatewayClient.Reply(200, mapper.createObjectNode()));
    when(mock.request(eq("PUT"), eq("/upstreams/new"), any()))
        .thenReturn(
            new GatewayClient.Reply(
                503, mapper.createObjectNode().put("error_msg", "unavailable")));
    p.setBackupDirectory(Files.createTempDirectory("gateway-uncertain-").toString());
    AuditService audit = mock(AuditService.class);
    when(audit.beginObject(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(7L);
    GatewayService service =
        new GatewayService(mock, p, new GatewayBackups(p, mapper), mapper, audit);
    assertThatThrownBy(
            () ->
                service.change(
                    "upstreams",
                    new GatewayService.Mutation(
                        "CREATE",
                        "new",
                        null,
                        "absent",
                        mapper.createObjectNode().put("type", "roundrobin")),
                    "admin"))
        .isInstanceOf(ApiException.class);
    verify(audit).finish(eq(7L), eq("PENDING"), contains("备份"));
    verify(audit, never()).finish(anyLong(), eq("SUCCESS"), anyString());
  }
}
