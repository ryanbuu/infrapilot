package dev.ryanbuu.infrapilot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.audit.AuditService;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties;
import dev.ryanbuu.infrapilot.dns.ZoneRegistry;
import dev.ryanbuu.infrapilot.forwarding.*;
import dev.ryanbuu.infrapilot.forwarding.ForwardingRules.Rule;
import dev.ryanbuu.infrapilot.forwarding.ForwardingService.Change;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class ForwardingServiceTest {
  @TempDir Path directory;
  ObjectMapper mapper = new ObjectMapper();
  ForwardingProperties properties;
  InfraPilotProperties app;
  ForwardingClient client;
  AuditService audit;
  ForwardingService service;

  @BeforeEach
  void setup() {
    properties = new ForwardingProperties();
    properties.setBackupDirectory(directory.toString());
    app = new InfraPilotProperties();
    client = spy(new ForwardingClient(properties, app, mapper));
    audit = mock(AuditService.class);
    when(audit.beginObject(any(), any(), any(), any(), any(), any(), any())).thenReturn(42L);
    service = newService();
  }

  ForwardingService newService() {
    return new ForwardingService(properties, app, client, new ZoneRegistry(app), audit, mapper);
  }

  Rule rule(String domain) {
    return new Rule(
        domain, "DOT", "only", 853, List.of("192.0.2.90"), "resolver.example.org", "测试");
  }

  Change create(String domain) {
    return new Change(service.view().path("revision").asText(), "create", null, rule(domain), null);
  }

  @Test
  void previewCrudAndBackupRestoreKeepBothNodesAligned() {
    ObjectNode original = service.view();
    Change create = create("special.example.org");
    assertThat(service.change(create, "admin", true).path("nodesValidated").asInt()).isEqualTo(2);
    assertThat(service.backups()).isEmpty();
    verifyNoInteractions(audit);
    ObjectNode saved = service.change(create, "admin", false);
    ObjectNode state = service.view();
    assertThat(state.path("consistent").asBoolean()).isTrue();
    assertThat(state.path("rules").size()).isEqualTo(2);
    assertThat(state.path("nodes").get(0).path("rules"))
        .isEqualTo(state.path("nodes").get(1).path("rules"));
    service.change(
        new Change(
            state.path("revision").asText(),
            "restore",
            null,
            null,
            saved.path("backupId").asText()),
        "admin",
        false);
    assertThat(service.view().path("rules")).isEqualTo(original.path("rules"));
    service.change(create("delete.example.org"), "admin", false);
    service.change(
        new Change(
            service.view().path("revision").asText(), "delete", "delete.example.org", null, null),
        "admin",
        false);
    assertThat(service.view().path("rules")).isEqualTo(original.path("rules"));
    assertThat(service.backups())
        .hasSize(4)
        .allSatisfy(b -> assertThat(b.path("phase").asText()).isEqualTo("complete"));
  }

  @Test
  void staleRevisionAndProtectedZoneRejectBeforeMutation() {
    Change old = create("old.example.org");
    service.change(create("fresh.example.org"), "admin", false);
    assertThatThrownBy(() -> service.change(old, "admin", false))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("版本");
    assertThatThrownBy(() -> service.change(create("intra.example.com"), "admin", false))
        .hasMessageContaining("权威");
    assertThat(service.backups()).hasSize(1);
  }

  @Test
  void lostSecondCommitAcknowledgementRestoresBothNodes() {
    ObjectNode before = service.view();
    AtomicBoolean fail = new AtomicBoolean(true);
    doAnswer(
            inv -> {
              ObjectNode response = (ObjectNode) inv.callRealMethod();
              if (((ForwardingProperties.Node) inv.getArgument(0)).id().equals("bind2")
                  && ((ObjectNode) inv.getArgument(1)).path("operation").asText().equals("commit")
                  && fail.getAndSet(false))
                throw new ApiException(HttpStatus.BAD_GATEWAY, "lost acknowledgement");
              return response;
            })
        .when(client)
        .call(any(), any());
    assertThatThrownBy(() -> service.change(create("lost.example.org"), "admin", false))
        .hasMessageContaining("恢复原配置");
    assertThat(service.view().path("rules")).isEqualTo(before.path("rules"));
    assertThat(service.view().path("writable").asBoolean()).isTrue();
    assertThat(service.backups().get(0).path("phase").asText()).isEqualTo("rolled-back");
    verify(audit).finish(eq(42L), eq("FAILED"), contains("恢复原配置"));
  }

  @Test
  void outageBlocksWritesAndRestartRecoversDurableJournal() {
    ObjectNode before = service.view();
    AtomicBoolean offline = new AtomicBoolean(false);
    doAnswer(
            inv -> {
              var node = (ForwardingProperties.Node) inv.getArgument(0);
              var request = (ObjectNode) inv.getArgument(1);
              if (node.id().equals("bind2")) {
                if (request.path("operation").asText().equals("commit")) offline.set(true);
                if (offline.get()) throw new ApiException(HttpStatus.BAD_GATEWAY, "offline");
              }
              return inv.callRealMethod();
            })
        .when(client)
        .call(any(), any());
    assertThatThrownBy(() -> service.change(create("outage.example.org"), "admin", false))
        .hasMessageContaining("暂停新变更");
    assertThat(service.view().path("writable").asBoolean()).isFalse();
    assertThat(service.backups().get(0).path("phase").asText()).isEqualTo("rollback-needed");
    offline.set(false);
    ObjectNode recovered = newService().view();
    assertThat(recovered.path("rules")).isEqualTo(before.path("rules"));
    assertThat(recovered.path("writable").asBoolean()).isTrue();
  }

  @Test
  void committedJournalFinishesWithoutUndoingAcknowledgedConfiguration() {
    AtomicBoolean once = new AtomicBoolean(true);
    doAnswer(
            inv -> {
              if (((ForwardingProperties.Node) inv.getArgument(0)).id().equals("bind2")
                  && ((ObjectNode) inv.getArgument(1)).path("operation").asText().equals("finalize")
                  && once.getAndSet(false))
                throw new ApiException(HttpStatus.BAD_GATEWAY, "lost finalize");
              return inv.callRealMethod();
            })
        .when(client)
        .call(any(), any());
    assertThatThrownBy(() -> service.change(create("finish.example.org"), "admin", false))
        .hasMessageContaining("收尾");
    ObjectNode recovered = newService().view();
    assertThat(recovered.path("rules").toString()).contains("finish.example.org");
    assertThat(recovered.path("writable").asBoolean()).isTrue();
    assertThat(service.backups().get(0).path("phase").asText()).isEqualTo("complete");
  }

  @Test
  void unsafeInputIsRejectedWithoutResolvingUpstreamHostnames() {
    assertThatThrownBy(() -> ForwardingRules.validate(List.of(rule("*.example.org"))))
        .hasMessageContaining("域名");
    Rule hostname =
        new Rule(
            "safe.example.org",
            "DOT",
            "only",
            853,
            List.of("resolver.example.org"),
            "resolver.example.org",
            "");
    assertThatThrownBy(() -> ForwardingRules.validate(List.of(hostname)))
        .hasMessageContaining("单播地址");
    assertThatThrownBy(
            () ->
                ForwardingRules.protect(
                    List.of(rule("example.com")), List.of("intra.example.com.")))
        .hasMessageContaining("权威");
  }
}
