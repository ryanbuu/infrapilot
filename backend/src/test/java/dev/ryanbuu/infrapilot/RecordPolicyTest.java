package dev.ryanbuu.infrapilot;

import static org.assertj.core.api.Assertions.*;

import dev.ryanbuu.infrapilot.config.InfraPilotProperties.Zone;
import dev.ryanbuu.infrapilot.dns.*;
import dev.ryanbuu.infrapilot.dns.DnsModels.Change;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

class RecordPolicyTest {
  RecordPolicy policy = new RecordPolicy();
  Zone zone = new Zone("test", "example.com.", "", false, null, null, List.of(), List.of());

  @Test
  void dhcidProtectsAllTypesAtTheSameOwner() throws Exception {
    Name n = Name.fromString("client.example.com.");
    Record dhcid =
        Record.fromString(
            n,
            Type.DHCID,
            DClass.IN,
            300,
            "AAEAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
            Name.root);
    assertThat(policy.protection(zone, n, Type.A, List.of(dhcid))).contains("DHCID");
    assertThat(policy.protection(zone, n, Type.TXT, List.of(dhcid))).contains("DHCID");
    assertThat(policy.protection(zone, n, Type.DHCID, List.of(dhcid))).isNotNull();
  }

  @Test
  void dnssecAndUnknownTypesCannotBeEdited() throws Exception {
    for (int type : List.of(Type.SOA, Type.NS, Type.DNSKEY, Type.RRSIG, Type.NSEC, 65534))
      assertThat(policy.protection(zone, Name.fromString("example.com."), type, List.of()))
          .isNotNull();
  }

  @Test
  void relativeNamesAreResolvedInsideTheSelectedZone() {
    assertThat(policy.name("@", zone).toString()).isEqualTo("example.com.");
    assertThat(policy.name("nas", zone).toString()).isEqualTo("nas.example.com.");
    assertThatThrownBy(() -> policy.name("not-example.com.", zone))
        .isInstanceOf(dev.ryanbuu.infrapilot.api.ApiException.class);
  }

  @Test
  void serviceBindingAndFingerprintRecordsUseNativeRdataValidation() {
    assertThat(
            policy.parse(
                new Change("svc", "HTTPS", 300, List.of("1 . alpn=\"h2,h3\""), null), zone))
        .hasSize(1);
    assertThat(
            policy.parse(
                new Change("_443._tcp.svc", "TLSA", 300, List.of("3 1 1 " + "ab".repeat(32)), null),
                zone))
        .hasSize(1);
    assertThat(
            policy.parse(
                new Change("ssh", "SSHFP", 300, List.of("4 2 " + "ab".repeat(32)), null), zone))
        .hasSize(1);
    assertThatThrownBy(
            () -> policy.parse(new Change("svc", "HTTPS", 300, List.of("invalid"), null), zone))
        .isInstanceOf(dev.ryanbuu.infrapilot.api.ApiException.class);
  }

  @Test
  void revisionDetectsTtlChangesAndIgnoresValueOrder() {
    var a =
        policy.parse(new Change("www", "A", 300, List.of("192.0.2.1", "192.0.2.2"), null), zone);
    var reordered =
        policy.parse(new Change("www", "A", 300, List.of("192.0.2.2", "192.0.2.1"), null), zone);
    var ttl =
        policy.parse(new Change("www", "A", 600, List.of("192.0.2.1", "192.0.2.2"), null), zone);
    assertThat(policy.revision(a)).isEqualTo(policy.revision(reordered));
    assertThat(policy.revision(a)).isNotEqualTo(policy.revision(ttl));
  }
}
