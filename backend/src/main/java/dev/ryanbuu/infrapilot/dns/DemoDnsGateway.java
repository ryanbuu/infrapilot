package dev.ryanbuu.infrapilot.dns;

import static org.springframework.http.HttpStatus.CONFLICT;

import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties.*;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties.Zone;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

@Component
@ConditionalOnProperty(name = "infrapilot.mode", havingValue = "demo", matchIfMissing = true)
public class DemoDnsGateway implements DnsGateway {
  private final Map<String, List<Record>> data = new ConcurrentHashMap<>();

  public DemoDnsGateway(ZoneRegistry registry) {
    for (Zone z : registry.all()) data.put(z.id(), seed(z));
  }

  @Override
  public synchronized List<Record> read(Zone zone) {
    return List.copyOf(data.computeIfAbsent(zone.id(), id -> seed(zone)));
  }

  @Override
  public synchronized void change(
      Zone z, List<Record> snapshot, List<Record> before, List<Record> after) {
    List<Record> current = data.get(z.id());
    if (!current.equals(snapshot)) throw new ApiException(CONFLICT, "区域已被其他操作更新，请刷新后重试");
    Record ref = before.isEmpty() ? after.getFirst() : before.getFirst();
    List<Record> next =
        new ArrayList<>(
            current.stream()
                .filter(r -> !(r.getName().equals(ref.getName()) && r.getType() == ref.getType()))
                .toList());
    next.addAll(after);
    SOARecord soa =
        (SOARecord) current.stream().filter(r -> r.getType() == Type.SOA).findFirst().orElseThrow();
    next.removeIf(r -> r.getType() == Type.SOA);
    next.add(
        new SOARecord(
            soa.getName(),
            DClass.IN,
            soa.getTTL(),
            soa.getHost(),
            soa.getAdmin(),
            soa.getSerial() + 1,
            3600,
            900,
            604800,
            300));
    data.put(z.id(), next);
  }

  @Override
  public DnsModels.ProbeResult probe(
      Zone z, Endpoint e, String name, String type, boolean primary) {
    List<Record> records = read(z);
    long serial =
        ((SOARecord)
                records.stream().filter(r -> r.getType() == Type.SOA).findFirst().orElseThrow())
            .getSerial();
    var answers =
        records.stream()
            .filter(
                r ->
                    r.getName().toString().equalsIgnoreCase(name)
                        && Type.string(r.getType()).equals(type))
            .map(Record::toString)
            .toList();
    return new DnsModels.ProbeResult(
        e.label(), true, "NOERROR", serial, answers, primary ? 3 : 6, null);
  }

  private List<Record> seed(Zone z) {
    List<Record> list = new ArrayList<>();
    add(
        list,
        z.name(),
        "SOA",
        3600,
        "resolver-a.intra.example.com. hostmaster.intra.example.com. 2026102101 3600 900 604800 300");
    add(list, z.name(), "NS", 3600, "resolver-a.intra.example.com.");
    add(list, z.name(), "NS", 3600, "resolver-b.intra.example.com.");
    if (z.id().equals("intra")) {
      String[][] services = {
        {"resolver-a", "10"},
        {"resolver-b", "11"},
        {"dhcp-a", "21"},
        {"dhcp-b", "22"},
        {"apphost", "30"},
        {"database", "40"},
        {"storage", "50"},
        {"node-a", "2"},
        {"node-b", "3"}
      };
      for (var s : services) add(list, s[0] + "." + z.name(), "A", 300, "192.0.2." + s[1]);
      add(list, "console.web." + z.name(), "A", 300, "192.0.2.30");
      add(list, "media.web." + z.name(), "A", 300, "192.0.2.30");
      add(list, "dashboard." + z.name(), "CNAME", 300, "console.web.intra.example.com.");
      add(list, z.name(), "TXT", 3600, "\"Managed with InfraPilot\"");
      add(list, "dhcp." + z.name(), "NS", 3600, "resolver-a.intra.example.com.");
    } else if (z.id().equals("dhcp")) {
      add(list, "workstation.internal." + z.name(), "A", 900, "198.51.100.101");
      add(
          list,
          "workstation.internal." + z.name(),
          "DHCID",
          900,
          "AAEAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=");
      add(list, "sensor.iot." + z.name(), "A", 900, "203.0.113.103");
    } else if (z.id().equals("reverse-infra")) {
      add(list, "10." + z.name(), "PTR", 300, "resolver-a.intra.example.com.");
      add(list, "11." + z.name(), "PTR", 300, "resolver-b.intra.example.com.");
      add(list, "21." + z.name(), "PTR", 300, "dhcp-a.intra.example.com.");
    } else add(list, "101." + z.name(), "PTR", 900, "workstation.internal.dhcp.intra.example.com.");
    return list;
  }

  private void add(List<Record> list, String name, String type, long ttl, String value) {
    try {
      list.add(
          Record.fromString(
              Name.fromString(name), Type.value(type), DClass.IN, ttl, value, Name.root));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
