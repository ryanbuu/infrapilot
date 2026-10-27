package dev.ryanbuu.infrapilot.dhcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties;
import java.util.*;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

final class ReservationDnsPolicy {
  static final String SKIP = "SKIP_DDNS";
  private final InfraPilotProperties properties;

  ReservationDnsPolicy(InfraPilotProperties properties) {
    this.properties = properties;
  }

  String domain() {
    return properties.getZones().stream()
        .filter(z -> z.writable() && !z.name().toLowerCase(Locale.ROOT).endsWith(".arpa."))
        .filter(z -> !z.name().toLowerCase(Locale.ROOT).replaceAll("\\.$", "").endsWith(".arpa"))
        .sorted(Comparator.comparing(z -> !z.id().equals("intra")))
        .map(z -> z.name().toLowerCase(Locale.ROOT).replaceAll("\\.$", ""))
        .findFirst().orElse(properties.getMode().equals("demo") ? "intra.example.com" : "");
  }

  boolean available(JsonNode cfg) {
    for (JsonNode hook : cfg.path("Dhcp4").path("hooks-libraries"))
      if (hook.path("library").asText().endsWith("/libdhcp_ddns_tuning.so")) return !domain().isEmpty();
    return false;
  }

  boolean isStatic(JsonNode host) {
    if (host == null) return false;
    for (JsonNode name : host.path("client-classes")) if (name.asText().equals(SKIP)) return true;
    return false;
  }

  ObjectNode decorate(ObjectNode view, JsonNode host) {
    boolean fixed = isStatic(host);
    String name = host.path("hostname").asText().replaceAll("\\.$", "");
    view.put("dnsMode", fixed ? "static" : "dynamic");
    view.put("fqdn", fixed ? name : "");
    if (fixed && !domain().isEmpty() && name.toLowerCase(Locale.ROOT).endsWith("." + domain()))
      view.put("hostname", name.substring(0, name.length() - domain().length() - 1));
    ArrayNode classes = view.withArray("clientClasses");
    for (int i = classes.size() - 1; i >= 0; i--)
      if (classes.get(i).asText().equals(SKIP)) classes.remove(i);
    return view;
  }

  void patch(ObjectNode host, JsonNode cfg, String mode) {
    if (!Set.of("static", "dynamic").contains(mode)) throw new ApiException(BAD_REQUEST, "DNS 维护方式无效");
    ArrayNode classes = host.withArray("client-classes");
    for (int i = classes.size() - 1; i >= 0; i--)
      if (classes.get(i).asText().equals(SKIP)) classes.remove(i);
    if (mode.equals("static")) {
      if (!available(cfg)) throw new ApiException(BAD_REQUEST, "节点的静态 DNS 策略尚未就绪");
      String name = host.path("hostname").asText().toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
      if (name.isBlank()) throw new ApiException(BAD_REQUEST, "静态 DNS 维护需要填写主机名");
      if (!name.endsWith("." + domain())) {
        if (name.contains(".")) throw new ApiException(BAD_REQUEST, "服务器名称须为短主机名或属于 " + domain());
        name += "." + domain();
      }
      host.put("hostname", name + ".");
      classes.add(SKIP);
    }
    if (classes.isEmpty()) host.remove("client-classes");
  }
}
