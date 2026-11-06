package dev.ryanbuu.infrapilot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.audit.AuditService;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties;
import dev.ryanbuu.infrapilot.dhcp.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DhcpServiceTest {
  final ObjectMapper mapper = new ObjectMapper();
  final AuditService audit = mock(AuditService.class);
  final InfraPilotProperties settings = new InfraPilotProperties();

  class Client extends KeaClient {
    String failNode = "";
    boolean failed;
    final List<String> commands = new ArrayList<>();
    final Map<String, JsonNode> arguments = new HashMap<>();

    Client() {
      super(new DhcpProperties(), settings, mapper);
    }

    public JsonNode call(String node, String cmd, JsonNode args) {
      commands.add(node + ":" + cmd);
      if (args != null) arguments.put(node + ":" + cmd, args.deepCopy());
      if (node.equals(failNode) && cmd.equals("config-set") && !failed) {
        failed = true;
        throw new ApiException(HttpStatus.BAD_GATEWAY, "test failure");
      }
      return super.call(node, cmd, args);
    }
  }

  final Client client = new Client();
  final DhcpService service = new DhcpService(client, mapper, audit, settings);

  ObjectNode reservation(String mac, String ip) {
    return mapper
        .createObjectNode()
        .put("mac", mac)
        .put("address", ip)
        .put("hostname", "test-device");
  }

  DhcpService.Change change(String revision, int id, JsonNode values) {
    return new DhcpService.Change(revision, id, values);
  }

  @Test
  void readsMergedLeasesAndNeverExposesConfigurationCredentials() {
    var v = service.view();
    assertThat(v.path("nodes").size()).isEqualTo(2);
    assertThat(v.path("leases").size()).isEqualTo(4);
    assertThat(v.path("leases").get(0).path("seenOn").size()).isEqualTo(2);
    assertThat(v.path("writable").asBoolean()).isTrue();
    assertThat(v.toString()).doesNotContain("password", "Authorization", "hooks-libraries");
  }

  @Test
  void previewDoesNotMutateAndReservationChangesPersistToBothNodes() {
    var v = service.view();
    var c =
        change(v.path("revision").asText(), 1, reservation("02:00:00:00:00:ee", "198.51.100.60"));
    service.change("reservation-save", c, "admin", true);
    assertThat(service.view().path("subnets").get(0).path("reservations").size()).isEqualTo(1);
    assertThat(client.commands).doesNotContain("kea1:config-set", "kea2:config-set");
    service.change("reservation-save", c, "admin", false);
    var after = service.view();
    assertThat(after.path("aligned").asBoolean()).isTrue();
    assertThat(after.path("subnets").get(0).path("reservations").size()).isEqualTo(2);
    assertThat(client.commands)
        .containsSubsequence(
            "kea2:config-set", "kea2:config-write", "kea1:config-set", "kea1:config-write");
    verify(audit).finish(0, "SUCCESS", "主备节点已校验、更新并持久化");
  }

  @Test
  void staleRevisionAndAddressInUseAreRejectedBeforeWrites() {
    var rev = service.view().path("revision").asText();
    assertThatThrownBy(
            () ->
                service.change(
                    "reservation-save",
                    change("old", 1, reservation("02:00:00:00:00:ee", "198.51.100.60")),
                    "a",
                    false))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status().value()).isEqualTo(409));
    assertThatThrownBy(
            () ->
                service.change(
                    "reservation-save",
                    change(rev, 1, reservation("02:00:00:00:00:ee", "198.51.100.101")),
                    "a",
                    false))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status().value()).isEqualTo(409));
    assertThat(client.commands).doesNotContain("kea1:config-set", "kea2:config-set");
  }

  @Test
  void secondNodeFailureRollsBackFirstNodeAndRecordsFailure() {
    var before = service.view();
    client.failNode = "kea1";
    assertThatThrownBy(
            () ->
                service.change(
                    "reservation-save",
                    change(
                        before.path("revision").asText(),
                        1,
                        reservation("02:00:00:00:00:ee", "198.51.100.60")),
                    "a",
                    false))
        .isInstanceOf(ApiException.class);
    assertThat(service.view().path("revision")).isEqualTo(before.path("revision"));
    verify(audit).finish(0, "FAILED", "配置同步失败，已恢复之前配置");
  }

  @Test
  void auditFailureBlocksAllConfigurationWrites() {
    doThrow(new IllegalStateException("database unavailable"))
        .when(audit)
        .beginObject(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
    var c =
        change(
            service.view().path("revision").asText(),
            1,
            reservation("02:00:00:00:00:ee", "198.51.100.60"));
    assertThatThrownBy(() -> service.change("reservation-save", c, "admin", false))
        .isInstanceOf(IllegalStateException.class);
    assertThat(client.commands).doesNotContain("kea1:config-set", "kea2:config-set");
  }

  @Test
  void subnetEditsRetainUnknownSettingsAndRejectOverlappingOrActiveLeaseRemoval() {
    var state = service.view();
    ObjectNode values = (ObjectNode) state.path("subnets").get(0).deepCopy();
    values.put("validLifetime", 3600);
    service.change("subnet-save", change(state.path("revision").asText(), 1, values), "a", false);
    assertThat(service.view().path("subnets").get(0).path("validLifetime").asInt()).isEqualTo(3600);
    values.putArray("pools").add("198.51.100.200 - 198.51.100.254");
    assertThatThrownBy(
            () ->
                service.change(
                    "subnet-save",
                    change(service.view().path("revision").asText(), 1, values),
                    "a",
                    true))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                service.change(
                    "subnet-delete",
                    change(service.view().path("revision").asText(), 1, mapper.createObjectNode()),
                    "a",
                    false))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void updatesPreserveEachNodesOwnHaSettingsAndUnknownSubnetOptions() {
    for (String node : List.of("kea1", "kea2")) {
      ObjectNode cfg =
          (ObjectNode) client.call(node, "config-get", null).path("arguments").deepCopy();
      ((ObjectNode) cfg.path("Dhcp4")).put("server-tag", node);
      ((ObjectNode) cfg.path("Dhcp4").path("subnet4").get(0))
          .putObject("user-context")
          .put("retained", true);
      client.call(node, "config-set", cfg);
    }
    var state = service.view();
    assertThat(state.path("aligned").asBoolean()).isTrue();
    service.change(
        "reservation-save",
        change(
            state.path("revision").asText(), 1, reservation("02:00:00:00:00:ee", "198.51.100.60")),
        "admin",
        false);
    for (String node : List.of("kea1", "kea2")) {
      var cfg = client.call(node, "config-get", null).path("arguments").path("Dhcp4");
      assertThat(cfg.path("server-tag").asText()).isEqualTo(node);
      assertThat(cfg.path("subnet4").get(0).path("user-context").path("retained").asBoolean())
          .isTrue();
    }
  }

  @Test
  void leaseReleaseAffectsBothCopiesAndRequestsDdnsCleanupOnce() {
    var l = service.view().path("leases").get(0);
    service.lease(
        new DhcpService.LeaseAction(
            l.path("revision").asText(), l.path("address").asText(), "release"),
        "admin");
    assertThat(service.view().path("leases").size()).isEqualTo(3);
    assertThat(client.commands).contains("kea1:lease4-del", "kea2:lease4-del");
    verify(audit).finish(0, "SUCCESS", "两台租约已释放，已请求清理 DDNS");
  }

  ObjectNode host(String node) {
    JsonNode cfg = client.call(node, "config-get", null).path("arguments").path("Dhcp4");
    for (JsonNode r : cfg.path("subnet4").get(0).path("reservations"))
      if (r.path("hw-address").asText().equals("02:00:00:00:00:ee")) return (ObjectNode) r;
    throw new AssertionError("Reservation not found");
  }

  void saveReservation(ObjectNode values) {
    service.change("reservation-save", change(service.view().path("revision").asText(), 1, values), "admin", false);
  }

  JsonNode hostOption(JsonNode host, int code) {
    for (JsonNode option : host.path("option-data"))
      if (option.path("space").asText("dhcp4").equals("dhcp4") && option.path("code").asInt() == code) return option;
    return mapper.nullNode();
  }

  @Test
  void hostNetworkAndBootOverridesPersistOnBothNodesAndClearBackToInheritance() {
    ObjectNode values = reservation("02:00:00:00:00:ee", "198.51.100.60")
        .put("router", "198.51.100.2").put("dnsServers", "192.0.2.20,192.0.2.21")
        .put("domain", "devices.example.com").put("searchDomains", "devices.example.com,example.com")
        .put("ntpServers", "192.0.2.30").put("mtu", 1450)
        .put("nextServer", "192.0.2.40").put("serverHostname", "boot.example.com")
        .put("bootFileName", "ipxe.efi").put("comment", "办公打印机");
    values.putArray("clientClasses").add("printer");
    values.putArray("extraOptions").addObject().put("code", 66).put("data", "boot.example.com")
        .put("always-send", true);
    service.change("reservation-save", change(service.view().path("revision").asText(), 1, values), "admin", true);
    assertThat(service.view().path("subnets").get(0).path("reservations").size()).isEqualTo(1);
    saveReservation(values);
    for (String node : List.of("kea1", "kea2")) {
      JsonNode r = host(node);
      assertThat(hostOption(r, 3).path("data").asText()).isEqualTo("198.51.100.2");
      assertThat(hostOption(r, 6).path("data").asText()).isEqualTo("192.0.2.20, 192.0.2.21");
      assertThat(hostOption(r, 26).path("data").asText()).isEqualTo("1450");
      assertThat(hostOption(r, 66).path("always-send").asBoolean()).isTrue();
      assertThat(r.path("next-server").asText()).isEqualTo("192.0.2.40");
      assertThat(r.path("client-classes").get(0).asText()).isEqualTo("printer");
      assertThat(r.path("user-context").path("comment").asText()).isEqualTo("办公打印机");
    }
    JsonNode rendered = service.view().path("subnets").get(0).path("reservations").get(1);
    assertThat(rendered.path("effective").path("dnsServers").asText()).isEqualTo("192.0.2.20, 192.0.2.21");
    assertThat(rendered.path("inherited").path("dnsServers").asText()).isEqualTo("192.0.2.10, 192.0.2.11");
    values.put("router", "").put("dnsServers", "").putNull("mtu");
    values.put("nextServer", "").put("serverHostname", "").put("bootFileName", "");
    values.putArray("clientClasses");
    saveReservation(values);
    rendered = service.view().path("subnets").get(0).path("reservations").get(1);
    assertThat(rendered.path("router").asText()).isEmpty();
    assertThat(rendered.path("effective").path("router").asText()).isEqualTo("198.51.100.1");
    for (String node : List.of("kea1", "kea2")) {
      assertThat(hostOption(host(node), 3).isNull()).isTrue();
      assertThat(host(node).has("next-server")).isFalse();
      assertThat(host(node).has("client-classes")).isFalse();
    }
  }

  @Test
  void partialHostEditsRetainVendorOptionsClassificationsAndPrivateMetadata() {
    saveReservation(reservation("02:00:00:00:00:ee", "198.51.100.60"));
    for (String node : List.of("kea1", "kea2")) {
      ObjectNode cfg = (ObjectNode) client.call(node, "config-get", null).path("arguments").deepCopy();
      ObjectNode r = (ObjectNode) cfg.path("Dhcp4").path("subnet4").get(0).path("reservations").get(1);
      r.put("next-server", "192.0.2.40").putArray("client-classes").add("existing-device");
      r.putObject("user-context").put("inventory-id", "retained").put("comment", "before");
      r.putArray("option-data").addObject().put("code", 6).put("data", "192.0.2.20").put("always-send", true);
      r.withArray("option-data").addObject().put("space", "vendor-4491").put("code", 1).put("data", "01:02").put("csv-format", false);
      client.call(node, "config-set", cfg);
    }
    saveReservation(reservation("02:00:00:00:00:ee", "198.51.100.60").put("dnsServers", "192.0.2.21").put("comment", "after"));
    for (String node : List.of("kea1", "kea2")) {
      JsonNode r = host(node);
      assertThat(hostOption(r, 6).path("always-send").asBoolean()).isTrue();
      assertThat(r.path("option-data").get(0).path("space").asText()).isEqualTo("vendor-4491");
      assertThat(r.path("option-data").get(0).path("csv-format").asBoolean()).isFalse();
      assertThat(r.path("next-server").asText()).isEqualTo("192.0.2.40");
      assertThat(r.path("client-classes").get(0).asText()).isEqualTo("existing-device");
      assertThat(r.path("user-context").path("inventory-id").asText()).isEqualTo("retained");
    }
  }

  @Test
  void displaysPoolSubnetAndGlobalInheritanceWithoutInventingHostOverrides() {
    for (String node : List.of("kea1", "kea2")) {
      ObjectNode cfg = (ObjectNode) client.call(node, "config-get", null).path("arguments").deepCopy();
      ObjectNode global = (ObjectNode) cfg.path("Dhcp4");
      global.putArray("option-data").addObject().put("code", 42).put("data", "192.0.2.42");
      ObjectNode s = (ObjectNode) global.path("subnet4").get(0);
      ((ObjectNode) s.path("pools").get(0)).putArray("option-data").addObject().put("code", 6).put("data", "192.0.2.99");
      s.withArray("reservations").addObject().put("hw-address", "02:00:00:00:00:ee").put("ip-address", "198.51.100.120");
      client.call(node, "config-set", cfg);
    }
    JsonNode r = service.view().path("subnets").get(0).path("reservations").get(1);
    assertThat(r.path("dnsServers").asText()).isEmpty();
    assertThat(r.path("effective").path("dnsServers").asText()).isEqualTo("192.0.2.99");
    assertThat(r.path("inherited").path("router").asText()).isEqualTo("198.51.100.1");
    assertThat(r.path("inherited").path("ntpServers").asText()).isEqualTo("192.0.2.42");
  }

  @Test
  void invalidHostSettingsAreRejectedBeforeAnyConfigurationWrite() {
    List<ObjectNode> invalid = List.of(
        mapper.createObjectNode().put("router", "203.0.113.1"),
        mapper.createObjectNode().put("dnsServers", "256.1.1.1"),
        mapper.createObjectNode().put("dnsServers", "192.0.2.1,,192.0.2.2"),
        mapper.createObjectNode().put("domain", "bad domain"),
        mapper.createObjectNode().put("searchDomains", "good.example,bad domain"),
        mapper.createObjectNode().put("ntpServers", "time.example.com"),
        mapper.createObjectNode().put("mtu", 65536),
        mapper.createObjectNode().put("mtu", 1.5),
        mapper.createObjectNode().put("nextServer", "::1"),
        mapper.createObjectNode().put("serverHostname", "x".repeat(65)),
        mapper.createObjectNode().put("bootFileName", "\u4e2d".repeat(43)),
        mapper.createObjectNode().put("clientClasses", "not-an-array"));
    String revision = service.view().path("revision").asText();
    for (ObjectNode patch : invalid) {
      ObjectNode values = reservation("02:00:00:00:00:ee", "198.51.100.60");
      values.setAll(patch);
      assertThatThrownBy(() -> service.change("reservation-save", change(revision, 1, values), "admin", false))
          .as(patch.toString()).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(400));
    }
    assertThat(client.commands).doesNotContain("kea1:config-set", "kea2:config-set");
  }

  @Test
  void extraOptionsCannotShadowManagedFieldsOrDuplicateOptions() {
    for (int mode = 0; mode < 3; mode++) {
      ObjectNode values = reservation("02:00:00:00:00:ee", "198.51.100.60");
      ArrayNode options = values.putArray("extraOptions");
      if (mode == 0) options.addObject().put("code", 6).put("data", "192.0.2.10");
      if (mode == 1) {
        options.addObject().put("code", 66).put("data", "a");
        options.addObject().put("code", 66).put("data", "b");
      }
      if (mode == 2) options.addObject().put("code", 66).put("data", "a").put("always-send", true).put("never-send", true);
      assertThatThrownBy(() -> saveReservation(values)).isInstanceOf(ApiException.class);
    }
    assertThat(client.commands).doesNotContain("kea1:config-set", "kea2:config-set");
  }

  void serverDomain() {
    settings.setZones(List.of(new InfraPilotProperties.Zone("intra", "intra.example.com.",
        "Servers", false, null, null, null, null)));
  }

  @Test
  void creatingAnAlreadyReservedMacCannotOverwriteItsAddressOrOptions() {
    ObjectNode values = reservation("02:00:00:00:00:ee", "198.51.100.60").put("dnsServers", "192.0.2.44");
    saveReservation(values);
    JsonNode before = service.view();
    client.commands.clear();
    ObjectNode duplicate = reservation("02:00:00:00:00:ee", "198.51.100.61");
    assertThatThrownBy(() -> service.change("reservation-create", change(before.path("revision").asText(), 1, duplicate), "admin", false))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(409));
    assertThat(service.view().path("subnets")).isEqualTo(before.path("subnets"));
    assertThat(client.commands).doesNotContain("kea1:config-set", "kea2:config-set");
  }

  @Test
  void leaseReservationMatchingUsesSubnetAndMacEvenWhenReservedIpDiffers() {
    var state = service.view();
    service.change("reservation-create", change(state.path("revision").asText(), 2,
        reservation("02:00:00:00:00:01", "203.0.113.60")), "admin", false);
    assertThat(service.view().path("leases").get(0).path("reserved").asBoolean()).isFalse();
    saveReservation(reservation("02:00:00:00:00:01", "198.51.100.60"));
    JsonNode lease = service.view().path("leases").get(0);
    assertThat(lease.path("reserved").asBoolean()).isTrue();
    assertThat(lease.path("reservationAddress").asText()).isEqualTo("198.51.100.60");
    assertThat(lease.path("address").asText()).isEqualTo("198.51.100.101");
  }

  @Test
  void staticReservationsUseMainZoneAndPreserveTheActualLeaseHostname() {
    serverDomain();
    saveReservation(reservation("02:00:00:00:00:01", "198.51.100.101").put("dnsMode", "static"));
    JsonNode state = service.view(), lease = state.path("leases").get(0);
    assertThat(state.path("staticDnsAvailable").asBoolean()).isTrue();
    assertThat(lease.path("hostname").asText()).isEqualTo("device-1.internal.dhcp.intra.example.com.");
    assertThat(lease.path("reservationFqdn").asText()).isEqualTo("test-device.intra.example.com");
    assertThat(lease.path("reservationDnsMode").asText()).isEqualTo("static");
    JsonNode host = state.path("subnets").get(0).path("reservations").get(1);
    assertThat(host.path("hostname").asText()).isEqualTo("test-device");
    assertThat(host.path("clientClasses").toString()).doesNotContain("SKIP_DDNS");
    for (String node : List.of("kea1", "kea2")) {
      JsonNode cfg = client.call(node, "config-get", null).path("arguments").path("Dhcp4");
      JsonNode nativeHost = cfg.path("subnet4").get(0).path("reservations").get(1);
      assertThat(nativeHost.path("hostname").asText()).isEqualTo("test-device.intra.example.com.");
      assertThat(nativeHost.path("client-classes").get(0).asText()).isEqualTo("SKIP_DDNS");
    }
  }

  @Test
  void staticReservationsBlockDdnsResendAndReleasePreservesStaticDns() {
    serverDomain();
    saveReservation(reservation("02:00:00:00:00:01", "198.51.100.101").put("dnsMode", "static"));
    JsonNode lease = service.view().path("leases").get(0);
    assertThatThrownBy(() -> service.lease(new DhcpService.LeaseAction(lease.path("revision").asText(),
        lease.path("address").asText(), "resend-ddns"), "admin"))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(400));
    assertThat(client.commands).doesNotContain("kea1:lease4-resend-ddns", "kea2:lease4-resend-ddns");
    service.lease(new DhcpService.LeaseAction(lease.path("revision").asText(), lease.path("address").asText(), "release"), "admin");
    for (String node : List.of("kea1", "kea2"))
      assertThat(client.arguments.get(node + ":lease4-del").path("update-ddns").asBoolean()).isFalse();
    verify(audit).finish(0, "SUCCESS", "两台租约已释放，静态 DNS 记录保留");
  }

  @Test
  void staticDnsCannotBeEnabledWithoutTheNativePolicyHook() {
    serverDomain();
    for (String node : List.of("kea1", "kea2")) {
      ObjectNode cfg = (ObjectNode) client.call(node, "config-get", null).path("arguments").deepCopy();
      ((ObjectNode) cfg.path("Dhcp4")).remove("hooks-libraries");
      client.call(node, "config-set", cfg);
    }
    client.commands.clear();
    assertThat(service.view().path("staticDnsAvailable").asBoolean()).isFalse();
    assertThatThrownBy(() -> saveReservation(reservation("02:00:00:00:00:ee", "198.51.100.60").put("dnsMode", "static")))
        .isInstanceOf(ApiException.class);
    assertThat(client.commands).doesNotContain("kea1:config-set", "kea2:config-set");
  }

  @Test
  void failedRuntimeLoadReappliesOriginalConfigEvenWhenConfigGetIsUnchanged() {
    JsonNode before = service.view();
    client.failNode = "kea2";
    assertThatThrownBy(() -> saveReservation(reservation("02:00:00:00:00:ee", "198.51.100.60")))
        .isInstanceOf(ApiException.class);
    assertThat(client.commands.stream().filter(s -> s.equals("kea2:config-set")).count()).isEqualTo(2);
    assertThat(service.view().path("subnets")).isEqualTo(before.path("subnets"));
  }
}
