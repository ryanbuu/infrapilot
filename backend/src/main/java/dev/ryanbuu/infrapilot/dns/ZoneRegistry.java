package dev.ryanbuu.infrapilot.dns;

import static org.springframework.http.HttpStatus.NOT_FOUND;

import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.xbill.DNS.Name;

@Component
public class ZoneRegistry {
  private final List<Zone> zones;
  private volatile List<Zone> managed = List.of();

  public ZoneRegistry(InfraPilotProperties p) {
    if (!Set.of("demo", "live").contains(p.getMode()))
      throw new IllegalStateException("infrapilot.mode must be demo or live");
    if (p.getTimeoutSeconds() < 1 || p.getTimeoutSeconds() > 30)
      throw new IllegalStateException("DNS timeout must be 1..30 seconds");
    zones = p.getMode().equals("demo") ? demoZones() : List.copyOf(p.getZones());
    if (zones.isEmpty())
      throw new IllegalStateException("Live mode requires explicitly configured zones");
    Set<String> ids = new HashSet<>();
    for (Zone z : zones) {
      try {
        if (!Name.fromString(z.name()).isAbsolute()) throw new Exception();
      } catch (Exception e) {
        throw new IllegalStateException("Zone names must be absolute DNS names");
      }
      if (z.id() == null || !z.id().matches("[a-zA-Z0-9_-]{1,64}") || !ids.add(z.id()))
        throw new IllegalStateException("Invalid or duplicate zone id");
      if (z.primary() == null || z.primary().host() == null)
        throw new IllegalStateException("Zone requires a primary endpoint");
      if (p.getMode().equals("live") && !p.getKeys().containsKey(z.key()))
        throw new IllegalStateException("Zone requires an explicitly configured TSIG key");
    }
  }

  public List<Zone> all() {
    List<Zone> all = new ArrayList<>(zones);
    all.addAll(managed);
    return List.copyOf(all);
  }

  public List<Zone> managed() {
    return managed;
  }

  public synchronized void setManaged(List<Zone> values) {
    Set<String> ids = new HashSet<>(), names = new HashSet<>();
    for (Zone z : zones) {
      ids.add(z.id());
      names.add(z.name().toLowerCase(Locale.ROOT));
    }
    for (Zone z : values) {
      if (z.id() == null
          || !z.id().matches("bind-[a-f0-9]{16}")
          || !ids.add(z.id())
          || !names.add(z.name().toLowerCase(Locale.ROOT))
          || !z.name().endsWith(".")
          || z.primary() == null) throw new IllegalStateException("Invalid managed zone catalog");
    }
    managed = List.copyOf(values);
  }

  public Zone find(String id) {
    return all().stream()
        .filter(z -> z.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new ApiException(NOT_FOUND, "区域不存在"));
  }

  private List<Zone> demoZones() {
    Endpoint p = new Endpoint("bind1", "192.0.2.10", 53),
        s = new Endpoint("bind2", "192.0.2.11", 53);
    return List.of(
        new Zone(
            "intra",
            "intra.example.com.",
            "基础设施与内部服务",
            false,
            null,
            p,
            List.of(s),
            List.of("dhcp.intra.example.com.")),
        new Zone(
            "dhcp",
            "dhcp.intra.example.com.",
            "客户端动态注册 · Internal / IoT",
            true,
            null,
            p,
            List.of(s),
            List.of()),
        new Zone(
            "reverse-infra",
            "2.0.192.in-addr.arpa.",
            "基础设施反向解析",
            false,
            null,
            p,
            List.of(s),
            List.of()),
        new Zone(
            "reverse-internal",
            "100.51.198.in-addr.arpa.",
            "Internal 客户端反向解析",
            true,
            null,
            p,
            List.of(s),
            List.of()),
        new Zone(
            "reverse-iot",
            "113.0.203.in-addr.arpa.",
            "IoT 客户端反向解析",
            true,
            null,
            p,
            List.of(s),
            List.of()));
  }
}
