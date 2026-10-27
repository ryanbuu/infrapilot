package dev.ryanbuu.infrapilot.dhcp;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.audit.AuditService;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DhcpService {
  private final KeaClient client;
  private final ObjectMapper mapper;
  private final AuditService audit;
  private final ReservationSettings reservationSettings;

  public DhcpService(KeaClient c, ObjectMapper m, AuditService a) {
    client = c;
    mapper = m;
    audit = a;
    reservationSettings = new ReservationSettings(m);
  }

  public record Change(String revision, int subnetId, JsonNode values) {}

  public record LeaseAction(String revision, String address, String action) {}

  record Snapshot(
      Map<String, ObjectNode> configs,
      Map<String, ArrayNode> leases,
      List<ObjectNode> nodes,
      String revision,
      boolean aligned) {}

  private ObjectNode obj() {
    return mapper.createObjectNode();
  }

  private ArrayNode arr() {
    return mapper.createArrayNode();
  }

  private ApiException bad(String s) {
    return new ApiException(BAD_REQUEST, s);
  }

  private String hash(Object v) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(v)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private ObjectNode config(String id) {
    JsonNode d = client.call(id, "config-get", null).path("arguments").path("Dhcp4");
    if (!d.isObject()) throw new ApiException(BAD_GATEWAY, "DHCP 服务未返回有效配置");
    ObjectNode out = obj();
    out.set("Dhcp4", d);
    return out;
  }

  private List<ArrayNode> groups(ObjectNode cfg) {
    List<ArrayNode> out = new ArrayList<>();
    ObjectNode d = (ObjectNode) cfg.path("Dhcp4");
    out.add(d.withArray("subnet4"));
    for (JsonNode net : d.path("shared-networks")) out.add(((ObjectNode) net).withArray("subnet4"));
    return out;
  }

  private List<ObjectNode> subnets(ObjectNode cfg) {
    List<ObjectNode> out = new ArrayList<>();
    for (var g : groups(cfg)) for (var s : g) out.add((ObjectNode) s);
    return out;
  }

  private ObjectNode subnet(ObjectNode cfg, int id) {
    return subnets(cfg).stream().filter(s -> s.path("id").asInt() == id).findFirst().orElse(null);
  }

  private ArrayNode leases(String id) {
    ArrayNode all = arr();
    String from = "start";
    for (int i = 0; i < 40; i++) {
      ObjectNode args = obj().put("from", from).put("limit", 500);
      JsonNode page = client.call(id, "lease4-get-page", args).path("arguments").path("leases");
      if (!page.isArray() || page.isEmpty()) return all;
      all.addAll((ArrayNode) page);
      String next = page.get(page.size() - 1).path("ip-address").asText();
      if (next.equals(from)) throw new ApiException(BAD_GATEWAY, "DHCP 租约分页游标未前进");
      from = next;
      if (page.size() < 500) return all;
    }
    throw new ApiException(BAD_GATEWAY, "租约超过当前查询上限");
  }

  private Snapshot snapshot() {
    Map<String, ObjectNode> configs = new LinkedHashMap<>();
    Map<String, ArrayNode> leases = new LinkedHashMap<>();
    List<ObjectNode> nodes = new ArrayList<>();
    if (client.enabled())
      for (var n : client.nodes()) {
        ObjectNode view = obj().put("id", n.id()).put("label", n.label());
        nodes.add(view);
        try {
          ObjectNode cfg = config(n.id());
          JsonNode status =
              client
                  .call(n.id(), "status-get", null)
                  .path("arguments")
                  .path("high-availability")
                  .path(0);
          ArrayNode ls = leases(n.id());
          configs.put(n.id(), cfg);
          leases.put(n.id(), ls);
          view.put("online", true)
              .put("version", client.call(n.id(), "version-get", null).path("text").asText())
              .put("mode", status.path("ha-mode").asText())
              .put("role", status.path("ha-servers").path("local").path("role").asText())
              .put("state", status.path("ha-servers").path("local").path("state").asText())
              .put(
                  "inTouch",
                  status.path("ha-servers").path("remote").path("in-touch").asBoolean(false))
              .put("leaseCount", ls.size());
        } catch (Exception e) {
          view.put("online", false).put("error", "节点连接失败，请检查 DHCP 管理接口");
        }
      }
    List<List<ObjectNode>> sets = configs.values().stream().map(this::subnets).toList();
    boolean aligned =
        configs.size() == client.nodes().size()
            && configs.size() == 2
            && sets.get(0).equals(sets.get(1));
    return new Snapshot(configs, leases, nodes, hash(sets), aligned);
  }

  private boolean writable(Snapshot s) {
    return s.aligned()
        && s.nodes().stream()
            .allMatch(
                n ->
                    n.path("online").asBoolean()
                        && n.path("inTouch").asBoolean()
                        && Set.of("hot-standby", "load-balancing")
                            .contains(n.path("state").asText()));
  }

  private long expiry(JsonNode lease) {
    return lease.has("expire")
        ? lease.path("expire").asLong()
        : lease.path("cltt").asLong() + lease.path("valid-lft").asLong();
  }

  private boolean active(JsonNode lease) {
    return lease.path("state").asInt() == 0 && expiry(lease) > Instant.now().getEpochSecond();
  }

  private String option(JsonNode s, int code, String name) {
    for (JsonNode o : s.path("option-data"))
      if ((o.path("space").asText("dhcp4").equals("dhcp4"))
          && (o.path("code").asInt(-1) == code || o.path("name").asText().equals(name)))
        return o.path("data").asText();
    return "";
  }

  private ObjectNode subnetView(ObjectNode cfg, ObjectNode s) {
    ObjectNode v =
        obj()
            .put("id", s.path("id").asInt())
            .put("prefix", s.path("subnet").asText())
            .put("router", option(s, 3, "routers"))
            .put("dnsServers", option(s, 6, "domain-name-servers"))
            .put("domain", option(s, 15, "domain-name"))
            .put("searchDomains", option(s, 119, "domain-search"))
            .put(
                "validLifetime",
                s.path("valid-lifetime")
                    .asInt(cfg.path("Dhcp4").path("valid-lifetime").asInt(7200)));
    ArrayNode pools = v.putArray("pools");
    for (var p : s.path("pools")) pools.add(p.path("pool").asText());
    ArrayNode reservations = v.putArray("reservations");
    for (var r : s.path("reservations"))
      reservations.add(reservationSettings.view(cfg, s, r));
    v.set("reservationDefaults", reservationSettings.view(cfg, s, obj()).path("inherited"));
    return v;
  }

  private JsonNode leaseCopies(Snapshot s, String address) {
    ObjectNode out = obj();
    for (var e : s.leases().entrySet())
      for (var l : e.getValue())
        if (l.path("ip-address").asText().equals(address)) out.set(e.getKey(), l);
    return out;
  }

  public ObjectNode view() {
    Snapshot s = snapshot();
    ObjectNode out =
        obj()
            .put("enabled", client.enabled())
            .put("revision", s.revision())
            .put("aligned", s.aligned())
            .put("writable", writable(s));
    out.set("nodes", mapper.valueToTree(s.nodes()));
    ArrayNode subnetViews = out.putArray("subnets");
    if (!s.configs().isEmpty()) {
      ObjectNode cfg = s.configs().values().iterator().next();
      for (var sub : subnets(cfg)) subnetViews.add(subnetView(cfg, sub));
      ArrayNode classes = out.putArray("clientClasses");
      for (JsonNode c : cfg.path("Dhcp4").path("client-classes"))
        classes.add(c.path("name").asText());
    }
    Map<String, ObjectNode> merged = new TreeMap<>();
    for (var e : s.leases().entrySet())
      for (var l : e.getValue()) {
        String ip = l.path("ip-address").asText();
        ObjectNode v = merged.get(ip);
        if (v == null) {
          v =
              obj()
                  .put("address", ip)
                  .put("mac", l.path("hw-address").asText())
                  .put("clientId", l.path("client-id").asText())
                  .put("hostname", l.path("hostname").asText())
                  .put("subnetId", l.path("subnet-id").asInt())
                  .put("expiresAt", expiry(l))
                  .put("state", l.path("state").asInt())
                  .put("active", active(l))
                  .put("ddnsForward", l.path("fqdn-fwd").asBoolean())
                  .put("ddnsReverse", l.path("fqdn-rev").asBoolean())
                  .put("revision", hash(leaseCopies(s, ip)));
          v.putArray("seenOn");
          merged.put(ip, v);
        }
        v.withArray("seenOn").add(e.getKey());
      }
    out.set("leases", mapper.valueToTree(merged.values()));
    return out;
  }

  private long ip(String s) {
    if (s == null || !s.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw bad("请输入有效 IPv4 地址");
    long out = 0;
    for (String p : s.split("\\.")) {
      if (p.length() > 1 && p.startsWith("0")) throw bad("IPv4 地址不能含前导零");
      int n = Integer.parseInt(p);
      if (n > 255) throw bad("IPv4 地址超出范围");
      out = out * 256 + n;
    }
    return out;
  }

  private long[] network(String prefix) {
    try {
      String[] p = prefix.split("/");
      if (p.length != 2) throw bad("请输入 IPv4 CIDR 子网");
      int bits = Integer.parseInt(p[1]);
      if (bits < 1 || bits > 30) throw bad("子网前缀长度须在 1 到 30 之间");
      long a = ip(p[0]), mask = (0xffffffffL << (32 - bits)) & 0xffffffffL;
      if ((a & mask) != a) throw bad("CIDR 必须使用网络地址");
      return new long[] {a, a + (1L << (32 - bits)) - 1};
    } catch (NumberFormatException e) {
      throw bad("无效子网前缀");
    }
  }

  private void domain(String v) {
    if (v.isBlank()) return;
    if (v.length() > 253) throw bad("域名过长");
    for (String label : v.replaceAll("\\.$", "").split("\\."))
      if (!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) throw bad("域名格式无效");
  }

  private void setOption(ObjectNode s, int code, String name, String data) {
    ArrayNode options = s.withArray("option-data");
    ObjectNode previous = null;
    for (int i = options.size() - 1; i >= 0; i--) {
      JsonNode o = options.get(i);
      if (o.path("space").asText("dhcp4").equals("dhcp4")
          && (o.path("code").asInt(-1) == code || o.path("name").asText().equals(name))) {
        previous = (ObjectNode) o.deepCopy();
        options.remove(i);
      }
    }
    if (!data.isBlank()) {
      ObjectNode o = previous == null ? obj() : previous;
      o.put("name", name)
          .put("code", code)
          .put("space", "dhcp4")
          .put("csv-format", true)
          .put("data", data.trim());
      options.add(o);
    }
  }

  private void patch(ObjectNode cfg, Snapshot snap, String operation, Change change) {
    if (change.subnetId() < 1) throw bad("子网 ID 必须为正整数");
    ObjectNode s = subnet(cfg, change.subnetId());
    JsonNode values = change.values() == null ? obj() : change.values();
    if (operation.equals("subnet-delete")) {
      if (s == null) throw new ApiException(NOT_FOUND, "子网不存在");
      if (!s.path("reservations").isEmpty()
          || snap.leases().values().stream()
              .flatMap(a -> java.util.stream.StreamSupport.stream(a.spliterator(), false))
              .anyMatch(l -> l.path("subnet-id").asInt() == change.subnetId() && active(l)))
        throw new ApiException(CONFLICT, "子网仍有保留地址或有效租约，不能删除");
      for (ArrayNode g : groups(cfg))
        for (int i = g.size() - 1; i >= 0; i--)
          if (g.get(i).path("id").asInt() == change.subnetId()) g.remove(i);
      return;
    }
    if (operation.equals("subnet-save")) {
      String prefix = values.path("prefix").asText();
      long[] net = network(prefix);
      if (s != null && !s.path("subnet").asText().equals(prefix)) throw bad("已有子网前缀不可修改");
      for (var other : subnets(cfg))
        if (other.path("id").asInt() != change.subnetId()) {
          long[] range = network(other.path("subnet").asText());
          if (net[0] <= range[1] && range[0] <= net[1]) throw bad("子网与现有网络重叠");
        }
      if (s == null) {
        s = obj().put("id", change.subnetId()).put("subnet", prefix);
        cfg.with("Dhcp4").withArray("subnet4").add(s);
      }
      ArrayNode pools = arr();
      List<long[]> ranges = new ArrayList<>();
      if (!values.path("pools").isArray() || values.path("pools").isEmpty()) throw bad("至少需要一个地址池");
      Map<String, JsonNode> existing = new HashMap<>();
      for (var pool : s.path("pools"))
        existing.put(pool.path("pool").asText().replace(" ", ""), pool);
      for (var pool : values.path("pools")) {
        String[] parts = pool.asText().trim().split("\\s*-\\s*");
        if (parts.length != 2) throw bad("地址池格式为 起始IP - 结束IP");
        long a = ip(parts[0]), b = ip(parts[1]);
        if (a <= net[0] || b >= net[1] || a > b) throw bad("地址池必须位于子网内，且不能包含网络或广播地址");
        for (var r : ranges) if (a <= r[1] && r[0] <= b) throw bad("地址池存在重叠");
        ranges.add(new long[] {a, b});
        String key = parts[0] + "-" + parts[1];
        ObjectNode p =
            existing.containsKey(key) ? (ObjectNode) existing.get(key).deepCopy() : obj();
        p.put("pool", parts[0] + " - " + parts[1]);
        pools.add(p);
      }
      for (var ls : snap.leases().values())
        for (var l : ls)
          if (l.path("subnet-id").asInt() == change.subnetId() && active(l)) {
            long address = ip(l.path("ip-address").asText());
            boolean reserved = false;
            for (var r : s.path("reservations"))
              if (r.path("ip-address").asText().equals(l.path("ip-address").asText()))
                reserved = true;
            if (!reserved && ranges.stream().noneMatch(r -> address >= r[0] && address <= r[1]))
              throw bad("地址池不能排除仍有效且未保留的租约");
          }
      int life = values.path("validLifetime").asInt();
      if (life < 60 || life > 604800) throw bad("租期须在 60 到 604800 秒之间");
      s.set("pools", pools);
      s.put("valid-lifetime", life);
      String routers = values.path("router").asText();
      for (String router : routers.split(","))
        if (!router.isBlank()) {
          long a = ip(router.trim());
          if (a <= net[0] || a >= net[1]) throw bad("网关必须位于子网内");
        }
      String dns = values.path("dnsServers").asText();
      for (String d : dns.split(",")) if (!d.isBlank()) ip(d.trim());
      String dom = values.path("domain").asText();
      domain(dom);
      String search = values.path("searchDomains").asText();
      for (String d : search.split(",")) domain(d.trim());
      setOption(s, 3, "routers", routers);
      setOption(s, 6, "domain-name-servers", dns);
      setOption(s, 15, "domain-name", dom);
      setOption(s, 119, "domain-search", search);
      return;
    }
    if (s == null) throw new ApiException(NOT_FOUND, "子网不存在");
    if (!Set.of("reservation-save", "reservation-delete").contains(operation))
      throw bad("不支持的配置操作");
    String mac = values.path("mac").asText().toLowerCase(Locale.ROOT);
    if (!mac.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}")
        || (Integer.parseInt(mac.substring(0, 2), 16) & 1) != 0
        || mac.equals("00:00:00:00:00:00")) throw bad("请输入有效单播 MAC 地址");
    ArrayNode reservations = s.withArray("reservations");
    ObjectNode old = null;
    for (int i = reservations.size() - 1; i >= 0; i--)
      if (reservations.get(i).path("hw-address").asText().equalsIgnoreCase(mac)) {
        old = (ObjectNode) reservations.get(i).deepCopy();
        reservations.remove(i);
      }
    if (operation.equals("reservation-delete")) {
      if (old == null) throw new ApiException(NOT_FOUND, "保留地址不存在");
      return;
    }
    String address = values.path("address").asText();
    long[] net = network(s.path("subnet").asText());
    long a = ip(address);
    if (a <= net[0] || a >= net[1]) throw bad("保留地址必须位于该子网内");
    for (var r : reservations)
      if (r.path("ip-address").asText().equals(address))
        throw new ApiException(CONFLICT, "该地址已被其他设备保留");
    for (var ls : snap.leases().values())
      for (var l : ls)
        if (active(l)
            && l.path("ip-address").asText().equals(address)
            && !l.path("hw-address").asText().equalsIgnoreCase(mac))
          throw new ApiException(CONFLICT, "该地址正在被其他客户端使用");
    String hostname = values.path("hostname").asText();
    domain(hostname);
    ObjectNode r = old == null ? obj() : old;
    r.put("hw-address", mac).put("ip-address", address).put("hostname", hostname);
    reservationSettings.patch(r, values, s);
    reservations.add(r);
  }

  public synchronized Map<String, Long> change(
      String operation, Change c, String actor, boolean preview) {
    Snapshot before = snapshot();
    if (!writable(before)) throw new ApiException(CONFLICT, "主备节点必须连通、HA 正常且子网配置一致后才能修改");
    if (c.revision() == null || !c.revision().equals(before.revision()))
      throw new ApiException(CONFLICT, "DHCP 配置已变化，请刷新后重试");
    Map<String, ObjectNode> after = new LinkedHashMap<>();
    for (var e : before.configs().entrySet()) {
      ObjectNode cfg = e.getValue().deepCopy();
      patch(cfg, before, operation, c);
      client.call(e.getKey(), "config-test", cfg);
      after.put(e.getKey(), cfg);
    }
    if (preview) return Map.of("validatedNodes", (long) after.size());
    long id =
        audit.beginObject(
            actor,
            "dhcp4",
            operation.endsWith("delete")
                ? "DELETE"
                : operation.startsWith("reservation") ? "RESERVATION" : "CONFIG",
            "subnet/" + c.subnetId(),
            "DHCP4",
            subnets(before.configs().values().iterator().next()),
            subnets(after.values().iterator().next()));
    List<String> attempted = new ArrayList<>();
    List<String> order = new ArrayList<>(after.keySet());
    Collections.reverse(order);
    try {
      for (String node : order) {
        if (!config(node).equals(before.configs().get(node)))
          throw new ApiException(CONFLICT, "DHCP 配置被其他管理端修改");
        attempted.add(node);
        client.call(node, "config-set", after.get(node));
        client.call(node, "config-write", obj());
      }
    } catch (Exception e) {
      boolean restored = true;
      for (String node : attempted)
        try {
          ObjectNode current = config(node);
          if (current.equals(before.configs().get(node))) continue;
          if (!current.equals(after.get(node))) {
            restored = false;
            continue;
          }
          client.call(node, "config-set", before.configs().get(node));
          client.call(node, "config-write", obj());
        } catch (Exception rollback) {
          restored = false;
        }
      audit.finish(
          id,
          restored ? "FAILED" : "PENDING",
          restored ? "配置同步失败，已恢复之前配置" : "配置同步结果未确认，需检查主备节点并手工恢复");
      throw new ApiException(
          SERVICE_UNAVAILABLE, restored ? "配置同步失败，已回滚，请刷新" : "部分节点更新结果未确认，请查看审计并检查主备节点");
    }
    try {
      audit.finish(id, "SUCCESS", "主备节点已校验、更新并持久化");
    } catch (Exception e) {
      throw new ApiException(SERVICE_UNAVAILABLE, "DHCP 配置已更新，审计完成状态未保存，请核实后再操作");
    }
    return Map.of("auditId", id);
  }

  public synchronized Map<String, Long> lease(LeaseAction a, String actor) {
    ip(a.address());
    if (a.action() == null || !Set.of("release", "resend-ddns").contains(a.action()))
      throw bad("不支持的租约操作");
    Snapshot s = snapshot();
    if (!writable(s)) throw new ApiException(CONFLICT, "主备节点及 HA 状态正常后才能修改租约");
    JsonNode copies = leaseCopies(s, a.address());
    if (copies.isEmpty()) throw new ApiException(NOT_FOUND, "租约不存在");
    String mac = copies.elements().next().path("hw-address").asText();
    for (JsonNode copy : copies)
      if (!copy.path("hw-address").asText().equals(mac))
        throw new ApiException(CONFLICT, "两台节点的租约客户端不一致，请先检查 HA");
    if (a.action().equals("resend-ddns")) {
      JsonNode lease = copies.elements().next();
      if (!active(lease) || lease.path("hostname").asText().isBlank())
        throw bad("只有具备主机名的有效租约才能重发 DNS 更新");
    }
    if (!hash(copies).equals(a.revision())) throw new ApiException(CONFLICT, "租约已变化，请刷新后重试");
    long id =
        audit.beginObject(
            actor,
            "dhcp4",
            a.action().equals("release") ? "RELEASE" : "DDNS",
            a.address(),
            "DHCP4",
            copies,
            null);
    try {
      int index = 0;
      for (String node : s.configs().keySet()) {
        if (a.action().equals("resend-ddns") && index > 0) break;
        ObjectNode args = obj().put("ip-address", a.address());
        if (a.action().equals("release")) args.put("update-ddns", index == 0);
        client.call(node, a.action().equals("release") ? "lease4-del" : "lease4-resend-ddns", args);
        index++;
      }
    } catch (Exception e) {
      audit.finish(id, "PENDING", "租约操作结果未完全确认，请检查主备节点和 DNS");
      throw new ApiException(SERVICE_UNAVAILABLE, "租约操作结果未确认，请查看审计，不要重复提交");
    }
    audit.finish(
        id, "SUCCESS", a.action().equals("release") ? "两台租约已释放，已请求清理 DDNS" : "已请求重发动态 DNS 更新");
    return Map.of("auditId", id);
  }
}
