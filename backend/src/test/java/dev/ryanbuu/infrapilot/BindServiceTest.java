package dev.ryanbuu.infrapilot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.audit.AuditService;
import dev.ryanbuu.infrapilot.bind.*;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties;
import dev.ryanbuu.infrapilot.dns.ZoneRegistry;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class BindServiceTest {
  @TempDir Path directory;
  ObjectMapper mapper = new ObjectMapper();
  BindProperties properties;
  InfraPilotProperties app;
  BindClient client;
  AuditService audit;
  ZoneRegistry registry;
  BindService service;

  @BeforeEach
  void setup() {
    properties = new BindProperties();
    properties.setBackupDirectory(directory.toString());
    app = new InfraPilotProperties();
    client = spy(new BindClient(properties, app, mapper));
    audit = mock(AuditService.class);
    when(audit.beginObject(any(), any(), any(), any(), any(), any(), any())).thenReturn(42L);
    registry = new ZoneRegistry(app);
    service = fresh();
  }

  BindService fresh() {
    return new BindService(properties, app, client, audit, mapper, registry);
  }

  BindService.Change create(String source) {
    return new BindService.Change(
        service.view().path("revision").asText(),
        mapper
            .createObjectNode()
            .put("operation", "create")
            .put("source", source)
            .put("view", "_default"),
        null);
  }

  @Test
  void previewAndPublishRestoreBothNodes() {
    JsonNode original = service.view().path("nodes").get(0).path("files");
    BindService.Change c = create("acl new_acl { 198.51.100.0/24; };");
    assertThat(service.change(c, "admin", true).path("nodesValidated").asInt()).isEqualTo(2);
    assertThat(service.backups()).isEmpty();
    verifyNoInteractions(audit);
    ObjectNode result = service.change(c, "admin", false);
    assertThat(service.view().path("nodes").get(1).path("files").toString()).contains("new_acl");
    service.change(
        new BindService.Change(
            service.view().path("revision").asText(),
            mapper
                .createObjectNode()
                .put("operation", "restore")
                .put("backupId", result.path("backupId").asText()),
            null),
        "admin",
        false);
    assertThat(service.view().path("nodes").get(0).path("files").get(0).path("content"))
        .isEqualTo(original.get(0).path("content"));
    assertThat(service.backups())
        .hasSize(2)
        .allSatisfy(j -> assertThat(j.path("phase").asText()).isEqualTo("complete"));
  }

  @Test
  void staleRevisionStopsBeforeMutating() {
    BindService.Change c = create("acl stale { localhost; };");
    service.change(create("acl fresh { localhost; };"), "admin", false);
    assertThatThrownBy(() -> service.change(c, "admin", false)).hasMessageContaining("版本");
    assertThat(service.backups()).hasSize(1);
  }

  @Test
  void lostCommitAcknowledgementRestoresBothNodes() {
    JsonNode before = service.view().path("nodes").get(0).path("files");
    AtomicBoolean fail = new AtomicBoolean(true);
    doAnswer(
            inv -> {
              Object result = inv.callRealMethod();
              if (((BindProperties.Node) inv.getArgument(0)).id().equals("bind2")
                  && ((ObjectNode) inv.getArgument(1)).path("operation").asText().equals("commit")
                  && fail.getAndSet(false))
                throw new ApiException(HttpStatus.BAD_GATEWAY, "lost ack");
              return result;
            })
        .when(client)
        .call(any(), any());
    assertThatThrownBy(() -> service.change(create("acl lost { localhost; };"), "admin", false))
        .hasMessageContaining("已恢复");
    assertThat(service.view().path("nodes").get(0).path("files")).isEqualTo(before);
    assertThat(service.backups().get(0).path("phase").asText()).isEqualTo("rolled-back");
  }

  @Test
  void interruptedFinalizeIsRecoveredOnRestartWithoutUndoingCommit() {
    AtomicBoolean once = new AtomicBoolean(true);
    doAnswer(
            inv -> {
              if (((BindProperties.Node) inv.getArgument(0)).id().equals("bind2")
                  && ((ObjectNode) inv.getArgument(1)).path("operation").asText().equals("finalize")
                  && once.getAndSet(false))
                throw new ApiException(HttpStatus.BAD_GATEWAY, "offline");
              return inv.callRealMethod();
            })
        .when(client)
        .call(any(), any());
    assertThatThrownBy(() -> service.change(create("acl durable { localhost; };"), "admin", false))
        .hasMessageContaining("收尾");
    assertThat(fresh().view().path("nodes").get(0).path("files").toString()).contains("durable");
    assertThat(service.backups().get(0).path("phase").asText()).isEqualTo("complete");
  }

  @Test
  void unreachableNodeBlocksNewWritesAndRecoversPendingRollback() {
    AtomicBoolean offline = new AtomicBoolean(false);
    doAnswer(
            inv -> {
              var n = (BindProperties.Node) inv.getArgument(0);
              var q = (ObjectNode) inv.getArgument(1);
              if (n.id().equals("bind2")) {
                if (q.path("operation").asText().equals("commit")) offline.set(true);
                if (offline.get()) throw new ApiException(HttpStatus.BAD_GATEWAY, "offline");
              }
              return inv.callRealMethod();
            })
        .when(client)
        .call(any(), any());
    assertThatThrownBy(() -> service.change(create("acl pending { localhost; };"), "admin", false))
        .hasMessageContaining("暂停");
    assertThat(service.view().path("writable").asBoolean()).isFalse();
    offline.set(false);
    assertThat(fresh().view().path("writable").asBoolean()).isTrue();
    assertThat(service.view().path("nodes").get(0).path("files").toString())
        .doesNotContain("acl pending");
  }

  @Test
  void zoneCatalogPersistsAndDisappearsAfterDelete() {
    ObjectNode action =
        mapper
            .createObjectNode()
            .put("operation", "zone-create")
            .put("name", "lab.example.net")
            .put("ns1", "ns1.example.net")
            .put("ns2", "ns2.example.net");
    service.change(
        new BindService.Change(service.view().path("revision").asText(), action, null),
        "admin",
        false);
    assertThat(registry.all()).hasSize(6);
    fresh();
    assertThat(registry.managed()).hasSize(1);
    action.put("operation", "zone-delete");
    service.change(
        new BindService.Change(service.view().path("revision").asText(), action, null),
        "admin",
        false);
    assertThat(registry.all()).hasSize(5);
  }

  @Test
  void generatedKeysUseRequestedAlgorithmAndAreNeverPersistedAtGeneration() {
    String source =
        service
            .generateKey(
                mapper
                    .createObjectNode()
                    .put("name", "test.example.net")
                    .put("algorithm", "hmac-sha512"))
            .path("source")
            .asText();
    assertThat(source).contains("hmac-sha512");
    assertThat(service.backups()).isEmpty();
    verifyNoInteractions(audit);
    assertThatThrownBy(() -> service.generateKey(mapper.createObjectNode().put("name", "a\"; sh")))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void singleNodeChangeCanBeRestoredWithoutLookingForPeerTransaction() {
    ObjectNode action =
        mapper
            .createObjectNode()
            .put("operation", "create")
            .put("source", "acl local_only { localhost; };")
            .put("view", "_default")
            .put("node", "bind1");
    ObjectNode saved =
        service.change(
            new BindService.Change(service.view().path("revision").asText(), action, null),
            "admin",
            false);
    assertThat(service.view().path("nodes").get(0).path("files").toString()).contains("local_only");
    assertThat(service.view().path("nodes").get(1).path("files").toString())
        .doesNotContain("local_only");
    service.change(
        new BindService.Change(
            service.view().path("revision").asText(),
            mapper
                .createObjectNode()
                .put("operation", "restore")
                .put("backupId", saved.path("backupId").asText()),
            null),
        "admin",
        false);
    assertThat(service.view().path("nodes").get(0).path("files").toString())
        .doesNotContain("local_only");
  }
}
