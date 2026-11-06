package dev.ryanbuu.infrapilot.dhcp;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.ryanbuu.infrapilot.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Host options are patched individually: omitted fields and unrelated native options survive. */
final class ReservationSettings {
  private final ObjectMapper mapper;

  private record Option(String field, int code, String name) {}

  private static final List<Option> OPTIONS = List.of(
      new Option("router", 3, "routers"),
      new Option("dnsServers", 6, "domain-name-servers"),
      new Option("domain", 15, "domain-name"),
      new Option("searchDomains", 119, "domain-search"),
      new Option("ntpServers", 42, "ntp-servers"),
      new Option("mtu", 26, "interface-mtu"));

  ReservationSettings(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  private ApiException bad(String message) {
    return new ApiException(BAD_REQUEST, message);
  }

  private boolean matches(JsonNode option, Option spec) {
    return option.path("space").asText("dhcp4").equals("dhcp4")
        && (option.path("code").asInt(-1) == spec.code()
            || option.path("name").asText().equals(spec.name()));
  }

  private boolean managed(JsonNode option) {
    return OPTIONS.stream().anyMatch(spec -> matches(option, spec));
  }

  private JsonNode find(JsonNode scope, Option spec) {
    for (JsonNode o : scope.path("option-data")) if (matches(o, spec)) return o;
    return null;
  }

  private long ip(String value) {
    if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw bad("请输入有效 IPv4 地址");
    long out = 0;
    for (String part : value.split("\\.")) {
      if ((part.length() > 1 && part.startsWith("0")) || Integer.parseInt(part) > 255)
        throw bad("IPv4 地址格式无效");
      out = (out << 8) + Integer.parseInt(part);
    }
    return out;
  }

  private void domain(String value) {
    if (value.length() > 253 || value.isBlank()) throw bad("请输入有效域名");
    for (String label : value.replaceAll("\\.$", "").split("\\.", -1))
      if (!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))
        throw bad("域名格式无效");
  }

  private String text(JsonNode values, String field) {
    JsonNode v = values.path(field);
    if (v.isNull() || v.isMissingNode()) return "";
    if (!v.isTextual()) throw bad("设置值必须为文本");
    return v.asText().trim();
  }

  private boolean contains(JsonNode pool, String address) {
    try {
      String value = pool.path("pool").asText();
      long a = ip(address);
      if (value.contains("/")) {
        String[] parts = value.split("/");
        int bits = Integer.parseInt(parts[1]);
        if (bits < 0 || bits > 32) return false;
        long mask = bits == 0 ? 0 : (0xffffffffL << (32 - bits)) & 0xffffffffL;
        return (a & mask) == (ip(parts[0]) & mask);
      }
      String[] ends = value.split("\\s*-\\s*");
      return ends.length == 2 && a >= ip(ends[0].trim()) && a <= ip(ends[1].trim());
    } catch (RuntimeException e) {
      return false;
    }
  }

  private List<JsonNode> scopes(ObjectNode cfg, ObjectNode subnet, JsonNode host) {
    List<JsonNode> scopes = new ArrayList<>();
    JsonNode dhcp = cfg.path("Dhcp4");
    scopes.add(dhcp);
    // Reservation-assigned classes are known here; packet-dependent classes remain server policy.
    List<JsonNode> classes = new ArrayList<>();
    for (JsonNode c : dhcp.path("client-classes"))
      for (JsonNode assigned : host.path("client-classes"))
        if (assigned.asText().equals(c.path("name").asText())) classes.add(c);
    Collections.reverse(classes);
    scopes.addAll(classes);
    for (JsonNode network : dhcp.path("shared-networks"))
      for (JsonNode child : network.path("subnet4"))
        if (child.path("id").equals(subnet.path("id"))) scopes.add(network);
    scopes.add(subnet);
    for (JsonNode pool : subnet.path("pools"))
      if (contains(pool, host.path("ip-address").asText())) scopes.add(pool);
    return scopes;
  }

  ObjectNode view(ObjectNode cfg, ObjectNode subnet, JsonNode host) {
    ObjectNode out = mapper.createObjectNode()
        .put("mac", host.path("hw-address").asText())
        .put("address", host.path("ip-address").asText())
        .put("hostname", host.path("hostname").asText())
        .put("editable", host.has("hw-address"));
    ObjectNode inherited = out.putObject("inherited");
    ObjectNode effective = out.putObject("effective");
    for (Option spec : OPTIONS) {
      JsonNode own = find(host, spec);
      String fallback = "";
      for (JsonNode scope : scopes(cfg, subnet, host)) {
        JsonNode o = find(scope, spec);
        if (o != null) fallback = o.path("data").asText();
      }
      String value = own == null ? "" : own.path("data").asText();
      out.put(spec.field(), value);
      inherited.put(spec.field(), fallback);
      effective.put(spec.field(), own == null ? fallback : value);
    }
    for (String[] field : List.of(new String[]{"nextServer", "next-server"},
        new String[]{"serverHostname", "server-hostname"}, new String[]{"bootFileName", "boot-file-name"})) {
      out.put(field[0], host.path(field[1]).asText());
      String fallback = "";
      for (JsonNode scope : scopes(cfg, subnet, host))
        if (scope.has(field[1])) fallback = scope.path(field[1]).asText();
      inherited.put(field[0], fallback);
    }
    out.set("clientClasses", host.path("client-classes").isArray()
        ? host.path("client-classes").deepCopy() : mapper.createArrayNode());
    out.put("comment", host.path("user-context").path("comment").asText());
    ArrayNode extra = out.putArray("extraOptions");
    for (JsonNode o : host.path("option-data")) if (!managed(o)) extra.add(o.deepCopy());
    return out;
  }

  private void setOption(ObjectNode host, Option spec, String data) {
    JsonNode current = find(host, spec);
    if (current != null && current.path("data").asText().equals(data)) return;
    ArrayNode options = host.withArray("option-data");
    ObjectNode option = current == null ? mapper.createObjectNode() : current.deepCopy();
    for (int i = options.size() - 1; i >= 0; i--) if (matches(options.get(i), spec)) options.remove(i);
    if (!data.isBlank()) {
      option.put("name", spec.name()).put("code", spec.code()).put("space", "dhcp4")
          .put("csv-format", true).put("data", data);
      options.add(option);
    }
  }

  void patch(ObjectNode host, JsonNode values, ObjectNode subnet) {
    for (Option spec : OPTIONS) {
      if (!values.has(spec.field())) continue;
      String data;
      if (spec.field().equals("mtu")) {
        JsonNode v = values.path("mtu");
        if (v.isNull() || (v.isTextual() && v.asText().isBlank())) data = "";
        else {
          if (!((v.isIntegralNumber() && v.canConvertToLong()) || (v.isTextual() && v.asText().matches("[0-9]{1,5}"))))
            throw bad("MTU 必须为整数");
          long mtu = v.isTextual() ? Long.parseLong(v.asText()) : v.asLong();
          if (mtu < 68 || mtu > 65535) throw bad("MTU 须在 68 到 65535 之间");
          data = Long.toString(mtu);
        }
      } else {
        data = text(values, spec.field());
        JsonNode current = find(host, spec);
        if (current != null && current.path("data").asText().equals(data)) continue;
        if (!data.isEmpty()) {
          List<String> parts = Arrays.stream(data.replace('，', ',').split(",", -1)).map(String::trim).toList();
          if (parts.stream().anyMatch(String::isEmpty)) throw bad("多个设置值须用逗号分隔，不能包含空值");
          if (parts.size() > 16) throw bad("同一设置最多填写 16 个值");
          for (String value : parts) {
            if (Set.of("domain", "searchDomains").contains(spec.field())) domain(value);
            else {
              long address = ip(value);
              if (spec.field().equals("router")) {
                String[] prefix = subnet.path("subnet").asText().split("/");
                int bits = Integer.parseInt(prefix[1]);
                long network = ip(prefix[0]), broadcast = network + (1L << (32 - bits)) - 1;
                if (address <= network || address >= broadcast) throw bad("网关必须位于该子网内");
              }
            }
          }
          if (spec.field().equals("domain") && parts.size() != 1) throw bad("域名只能填写一个，多个域名请使用搜索域");
          data = String.join(", ", parts);
        }
      }
      setOption(host, spec, data);
    }
    for (String[] field : List.of(new String[]{"nextServer", "next-server"},
        new String[]{"serverHostname", "server-hostname"}, new String[]{"bootFileName", "boot-file-name"})) {
      if (!values.has(field[0])) continue;
      String value = text(values, field[0]);
      if (value.isEmpty()) host.remove(field[1]);
      else {
        if (field[0].equals("nextServer")) ip(value);
        int limit = field[0].equals("serverHostname") ? 64 : 128;
        if (value.getBytes(StandardCharsets.UTF_8).length > limit || value.chars().anyMatch(c -> c < 32 || c == 127))
          throw bad("网络启动设置过长或包含控制字符");
        host.put(field[1], value);
      }
    }
    if (values.has("clientClasses")) {
      JsonNode classes = values.path("clientClasses");
      if (!classes.isArray() || classes.size() > 32) throw bad("客户端分类须为列表，最多 32 项");
      Set<String> names = new LinkedHashSet<>();
      for (JsonNode c : classes) {
        if (!c.isTextual() || c.asText().isBlank() || c.asText().length() > 128
            || c.asText().chars().anyMatch(ch -> ch < 32 || ch == 127)) throw bad("客户端分类名称无效");
        if (!names.add(c.asText())) throw bad("客户端分类不能重复");
      }
      if (names.isEmpty()) host.remove("client-classes");
      else host.set("client-classes", mapper.valueToTree(names));
    }
    if (values.has("comment")) {
      String comment = text(values, "comment");
      if (comment.length() > 2048) throw bad("备注最多 2048 字符");
      if (!comment.isEmpty()) host.withObject("user-context").put("comment", comment);
      else if (host.path("user-context").isObject()) {
        ObjectNode context = (ObjectNode) host.path("user-context");
        context.remove("comment");
        if (context.isEmpty()) host.remove("user-context");
      }
    }
    if (values.has("extraOptions")) patchExtra(host, values.path("extraOptions"));
    if (host.path("option-data").isEmpty()) host.remove("option-data");
  }

  private void patchExtra(ObjectNode host, JsonNode extra) {
    if (!extra.isArray() || extra.size() > 64) throw bad("附加 DHCP 选项须为列表，最多 64 项");
    Set<String> seen = new HashSet<>();
    ArrayNode options = mapper.createArrayNode();
    for (JsonNode old : host.path("option-data")) if (managed(old)) options.add(old.deepCopy());
    for (JsonNode o : extra) {
      if (!o.isObject() || managed(o)) throw bad("网关、DNS 等常用选项请在对应字段设置");
      String space = o.path("space").asText("dhcp4"), name = o.path("name").asText();
      if (!space.matches("[A-Za-z][A-Za-z0-9_-]{0,127}")
          || (!name.isEmpty() && !name.matches("[A-Za-z][A-Za-z0-9_-]{0,127}"))) throw bad("DHCP 选项名称或空间无效");
      int code = o.path("code").asInt(-1);
      if (o.has("code") && (!o.path("code").isIntegralNumber() || !o.path("code").canConvertToInt() || code < (space.equals("dhcp4") ? 1 : 0)
          || code > (space.equals("dhcp4") ? 254 : 65535))) throw bad("DHCP 选项代码超出范围");
      if (name.isEmpty() && code < 0) throw bad("附加选项必须指定名称或代码");
      if ((!name.isEmpty() && !seen.add(space + ":name:" + name))
          || (code >= 0 && !seen.add(space + ":code:" + code))) throw bad("DHCP 选项不能重复");
      if (!o.path("data").isTextual() || o.path("data").asText().length() > 4096) throw bad("DHCP 选项值须为文本，最多 4096 字符");
      for (String flag : List.of("csv-format", "always-send", "never-send"))
        if (o.has(flag) && !o.path(flag).isBoolean()) throw bad("DHCP 选项开关值无效");
      if (o.path("always-send").asBoolean() && o.path("never-send").asBoolean()) throw bad("不能同时指定始终发送和禁止发送");
      options.add(o.deepCopy());
    }
    host.set("option-data", options);
  }
}
