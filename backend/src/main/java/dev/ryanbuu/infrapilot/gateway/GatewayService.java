package dev.ryanbuu.infrapilot.gateway;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.audit.AuditService;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@Service
public class GatewayService {
  public record Mutation(
      String action, String id, String parent, String revision, JsonNode value) {}

  public record Item(String id, String revision, JsonNode value) {}

  public record Listing(String kind, boolean available, String message, List<Item> items) {}

  private record Plan(
      GatewayResource resource,
      Mutation mutation,
      JsonNode before,
      JsonNode after,
      String revision,
      String validation) {}

  private final GatewayClient client;
  private final GatewayProperties p;
  private final GatewayBackups backups;
  private final ObjectMapper mapper;
  private final AuditService audit;

  public GatewayService(
      GatewayClient client,
      GatewayProperties p,
      GatewayBackups backups,
      ObjectMapper mapper,
      AuditService audit) {
    this.client = client;
    this.p = p;
    this.backups = backups;
    this.mapper = mapper;
    this.audit = audit;
  }

  private JsonNode require(GatewayClient.Reply reply, JsonNode value) {
    if (reply.status() >= 200 && reply.status() < 300) return reply.data();
    HttpStatus status =
        reply.status() == 400
            ? HttpStatus.BAD_REQUEST
            : reply.status() == 404
                ? HttpStatus.NOT_FOUND
                : reply.status() == 409 ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY;
    String detail =
        GatewaySecrets.safeError(
            reply.data().path("error_msg").asText("管理接口拒绝请求"), value, p.getApiKey());
    if (reply.status() == 401 || reply.status() == 403) detail = "请检查服务端管理凭据和访问权限";
    throw new ApiException(status, "网关：" + detail);
  }

  public Listing resources(String kind, String parent) {
    GatewayResource r = GatewayResource.of(kind);
    var reply = client.request("GET", r.path(parent, null), null);
    if (r == GatewayResource.STREAM_ROUTES && reply.status() == 400)
      return new Listing(kind, false, "当前实例未启用 TCP / UDP 转发", List.of());
    if (reply.status() == 404) return new Listing(kind, false, "当前实例不支持此资源", List.of());
    JsonNode data = require(reply, null);
    JsonNode list = data.has("list") ? data.path("list") : data.path("node").path("nodes");
    List<Item> items = new ArrayList<>();
    if (list.isArray())
      for (JsonNode node : list) {
        String key = node.path("key").asText();
        String marker = "/" + r.id + "/";
        int offset = key.indexOf(marker);
        String id =
            offset >= 0
                ? key.substring(offset + marker.length())
                : node.path("value")
                    .path(r == GatewayResource.CONSUMERS ? "username" : "id")
                    .asText();
        if (r == GatewayResource.CONSUMERS && id.contains("/credentials/")) continue;
        items.add(new Item(id, revision(node), GatewaySecrets.redact(node.path("value"))));
      }
    return new Listing(kind, true, "", items);
  }

  public Map<String, Object> overview() {
    if (!client.enabled()) return Map.of("enabled", false, "name", "Application Gateway");
    List<Map<String, Object>> counts = new ArrayList<>();
    for (GatewayResource r : GatewayResource.values()) {
      if (r == GatewayResource.CREDENTIALS) continue;
      try {
        Listing list = resources(r.id, null);
        counts.add(
            Map.of(
                "kind",
                r.id,
                "label",
                r.label,
                "count",
                list.items.size(),
                "available",
                list.available,
                "message",
                list.message));
      } catch (ApiException e) {
        counts.add(
            Map.of(
                "kind",
                r.id,
                "label",
                r.label,
                "count",
                0,
                "available",
                false,
                "message",
                e.getMessage()));
      }
    }
    JsonNode plugins = plugins();
    return Map.of(
        "enabled",
        true,
        "name",
        "Application Gateway",
        "version",
        client.version(),
        "endpoint",
        client.endpoint(),
        "resources",
        counts,
        "plugins",
        plugins.size(),
        "demo",
        client.demo(),
        "checkedAt",
        Instant.now().toString());
  }

  public JsonNode plugins() {
    return require(client.request("GET", "/plugins/list", null), null);
  }

  public JsonNode schema(String kind, String plugin) {
    String path;
    if (plugin != null && !plugin.isBlank())
      path = "/schema/plugins/" + GatewayResource.segment(plugin);
    else path = "/schema/" + GatewayResource.of(kind).schema;
    var reply = client.request("GET", path, null);
    if (reply.status() == 400 || reply.status() == 404)
      return mapper.createObjectNode().put("available", false);
    return require(reply, null);
  }

  public JsonNode runtime() {
    if (client.demo())
      return mapper.valueToTree(
          Map.of(
              "available",
              true,
              "readOnly",
              true,
              "configuration",
              Map.of(
                  "apisix",
                  Map.of(
                      "node_listen",
                      9080,
                      "ssl",
                      Map.of("enable", true, "listen", List.of(Map.of("port", 9443)))))));
    if (p.getRuntimeConfigFile().isBlank())
      return mapper.createObjectNode().put("available", false);
    try {
      Path file = Path.of(p.getRuntimeConfigFile());
      if (Files.size(file) > 2_000_000) throw new IllegalStateException();
      String content = Files.readString(file);
      Object data = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
      return mapper
          .createObjectNode()
          .put("available", true)
          .put("readOnly", true)
          .put("revision", hash(content))
          .set("configuration", GatewaySecrets.redact(mapper.valueToTree(data)));
    } catch (Exception e) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "无法读取节点配置，请检查配置文件挂载");
    }
  }

  private JsonNode read(GatewayResource r, String id, String parent) {
    // APISIX 3.13 removes the primary private key from single-SSL GET responses.
    // Its collection contains the stored encrypted value, needed for lossless edits.
    if (r == GatewayResource.SSLS) {
      JsonNode data = require(client.request("GET", "/ssls", null), null);
      for (JsonNode node : data.path("list"))
        if (node.path("key").asText().equals("/apisix/ssls/" + id)) return node;
      return NullNode.instance;
    }
    var reply = client.request("GET", r.path(parent, id), null);
    if (reply.status() == 404) return NullNode.instance;
    JsonNode node = require(reply, null);
    return node.has("node") ? node.path("node") : node;
  }

  private JsonNode canonical(JsonNode n) {
    if (n.isObject()) {
      ObjectNode out = mapper.createObjectNode();
      List<String> keys = new ArrayList<>();
      n.fieldNames().forEachRemaining(keys::add);
      Collections.sort(keys);
      keys.forEach(k -> out.set(k, canonical(n.get(k))));
      return out;
    }
    if (n.isArray()) {
      ArrayNode out = mapper.createArrayNode();
      n.forEach(v -> out.add(canonical(v)));
      return out;
    }
    return n;
  }

  private String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String revision(JsonNode raw) {
    return raw.isNull() ? "absent" : hash(canonical(raw).toString());
  }

  private Plan prepare(String kind, Mutation m) {
    GatewayResource r = GatewayResource.of(kind);
    if (m == null || m.action == null || !Set.of("CREATE", "UPDATE", "DELETE").contains(m.action))
      throw new ApiException(HttpStatus.BAD_REQUEST, "请选择新增、修改或删除操作");
    if (m.id == null || m.id.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "请填写资源 ID");
    r.path(m.parent, m.id);
    JsonNode current = read(r, m.id, m.parent);
    String version = revision(current);
    if (m.action.equals("CREATE")) {
      if (!current.isNull()) throw new ApiException(HttpStatus.CONFLICT, "资源 ID 已存在，请使用其他 ID");
    } else if (current.isNull() || !version.equals(m.revision)) {
      throw new ApiException(HttpStatus.CONFLICT, "资源已变化，请刷新并重新预览");
    }
    JsonNode before = current.isNull() ? NullNode.instance : current.path("value");
    JsonNode after = NullNode.instance;
    String validation = "删除时将检查资源引用关系";
    if (m.action.equals("DELETE")
        && r == GatewayResource.CONSUMERS
        && !resources("credentials", m.id).items.isEmpty())
      throw new ApiException(HttpStatus.CONFLICT, "此消费者仍有独立凭据，请先删除或迁移凭据");
    if (!m.action.equals("DELETE")) {
      if (m.value == null || !m.value.isObject() || m.value.toString().length() > 4_000_000)
        throw new ApiException(HttpStatus.BAD_REQUEST, "配置必须为 JSON 对象，最大 4 MB");
      ObjectNode value = (ObjectNode) GatewaySecrets.resolve(m.value, before);
      value.remove(List.of("create_time", "update_time"));
      if (value.path("upstream").isObject())
        ((ObjectNode) value.path("upstream")).remove(List.of("create_time", "update_time"));
      if (r == GatewayResource.CONSUMERS) value.put("username", m.id);
      else if (Set.of(GatewayResource.SECRETS, GatewayResource.PLUGIN_METADATA).contains(r))
        value.remove("id");
      else value.put("id", m.id);
      after = value;
      // 3.13's generic validator dereferences a missing schema for these resources (HTTP 500).
      // Their provider/plugin-specific checker runs in the real resource PUT handler.
      if (r == GatewayResource.SECRETS || r == GatewayResource.PLUGIN_METADATA)
        validation = "此资源由网关在保存时进行专属校验";
      else {
        var check = client.request("POST", "/schema/validate/" + r.id, after);
        if (check.status() == 404) validation = "此版本未提供预校验，保存时由网关校验";
        else {
          require(check, after);
          validation = "Schema 校验通过";
        }
      }
    }
    return new Plan(r, m, before, after, version, validation);
  }

  private Map<String, Object> view(Plan plan) {
    return Map.of(
        "kind",
        plan.resource.id,
        "id",
        plan.mutation.id,
        "action",
        plan.mutation.action,
        "revision",
        plan.revision,
        "validation",
        plan.validation,
        "before",
        GatewaySecrets.redact(plan.before),
        "after",
        GatewaySecrets.redact(plan.after));
  }

  public Map<String, Object> preview(String kind, Mutation m) {
    return view(prepare(kind, m));
  }

  public synchronized Map<String, Object> change(String kind, Mutation m, String actor) {
    return apply(prepare(kind, m), actor);
  }

  private Map<String, Object> apply(Plan plan, String actor) {
    ObjectNode snapshot =
        mapper
            .createObjectNode()
            .put("kind", plan.resource.id)
            .put("resourceId", plan.mutation.id)
            .put("parent", plan.mutation.parent == null ? "" : plan.mutation.parent)
            .put("action", plan.mutation.action)
            .put("actor", actor)
            .put("revision", plan.revision);
    snapshot.set("before", plan.before);
    snapshot.set("after", plan.after);
    String backup = backups.save(snapshot);
    long id =
        audit.beginObject(
            actor,
            "gateway/" + plan.resource.id,
            plan.mutation.action,
            plan.mutation.id,
            "GATEWAY",
            GatewaySecrets.redact(plan.before),
            GatewaySecrets.redact(plan.after));
    try {
      var result =
          client.request(
              plan.mutation.action.equals("DELETE") ? "DELETE" : "PUT",
              plan.resource.path(plan.mutation.parent, plan.mutation.id),
              plan.after.isNull() ? null : plan.after);
      require(result, plan.after);
    } catch (ApiException e) {
      String status = e.status().is4xxClientError() ? "FAILED" : "PENDING";
      audit.finish(id, status, "备份 " + backup + " · " + e.getMessage());
      throw e;
    }
    try {
      JsonNode current = read(plan.resource, plan.mutation.id, plan.mutation.parent);
      if ((plan.after.isNull() && !current.isNull())
          || (!plan.after.isNull()
              && (current.isNull()
                  || !confirmed(plan.resource, current.path("value"), plan.after))))
        throw new ApiException(HttpStatus.BAD_GATEWAY, "保存结果尚未确认，请刷新资源列表核查");
    } catch (Exception e) {
      audit.finish(id, "PENDING", "备份 " + backup + " · 管理接口写入完成，回读结果未确认");
      throw new ApiException(HttpStatus.BAD_GATEWAY, "保存结果尚未确认，请刷新资源列表核查");
    }
    audit.finish(id, "SUCCESS", "配置已回读确认 · 备份 " + backup);
    return Map.of("auditId", id, "backupId", backup, "verified", true);
  }

  private boolean confirmed(GatewayResource r, JsonNode actual, JsonNode expected) {
    if (r != GatewayResource.SSLS) return contains(actual, expected);
    ObjectNode comparable = (ObjectNode) expected.deepCopy();
    for (String field : List.of("key", "keys")) {
      JsonNode value = expected.path(field);
      if (value.isTextual() && value.asText().startsWith("---")) {
        if (!actual.path(field).isTextual() || actual.path(field).asText().isBlank()) return false;
        comparable.remove(field);
      } else if (value.isArray()) {
        // A fresh PEM key is encrypted by APISIX; the corresponding public cert is checked below.
        if (!actual.path(field).isArray() || actual.path(field).size() != value.size())
          return false;
        for (int i = 0; i < value.size(); i++)
          if (!value.get(i).asText().startsWith("---")
              && !value.get(i).equals(actual.path(field).path(i))) return false;
        comparable.remove(field);
      }
    }
    return contains(actual, comparable);
  }

  private boolean contains(JsonNode actual, JsonNode expected) {
    if (expected.isObject()) {
      if (!actual.isObject()) return false;
      var fields = expected.fields();
      while (fields.hasNext()) {
        var e = fields.next();
        if (!contains(actual.path(e.getKey()), e.getValue())) return false;
      }
      return true;
    }
    if (expected.isArray()) {
      if (!actual.isArray() || actual.size() != expected.size()) return false;
      for (int i = 0; i < expected.size(); i++)
        if (!contains(actual.get(i), expected.get(i))) return false;
      return true;
    }
    if (expected.isNumber() && actual.isNumber())
      return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
    return expected.equals(actual);
  }

  public List<JsonNode> backups() {
    return backups.list();
  }

  private Mutation restoreMutation(ObjectNode backup, String expectedRevision) {
    String kind = backup.path("kind").asText(),
        id = backup.path("resourceId").asText(),
        parent = backup.path("parent").asText();
    GatewayResource r = GatewayResource.of(kind);
    JsonNode current = read(r, id, parent);
    String version = revision(current);
    if (expectedRevision != null && !version.equals(expectedRevision))
      throw new ApiException(HttpStatus.CONFLICT, "资源已变化，请重新预览恢复");
    JsonNode value = backup.path("before");
    if (value.isNull() && current.isNull())
      throw new ApiException(HttpStatus.CONFLICT, "资源已处于目标状态");
    return new Mutation(
        value.isNull() ? "DELETE" : current.isNull() ? "CREATE" : "UPDATE",
        id,
        parent,
        version,
        value);
  }

  public Map<String, Object> previewRestore(String id) {
    ObjectNode backup = backups.read(id);
    return preview(backup.path("kind").asText(), restoreMutation(backup, null));
  }

  public synchronized Map<String, Object> restore(String id, String revision, String actor) {
    if (revision == null || revision.isBlank())
      throw new ApiException(HttpStatus.CONFLICT, "请先预览恢复");
    ObjectNode backup = backups.read(id);
    return change(backup.path("kind").asText(), restoreMutation(backup, revision), actor);
  }

  public JsonNode export(boolean includeSecrets, String actor) {
    ObjectNode out =
        mapper
            .createObjectNode()
            .put("format", "infrapilot-gateway/v1")
            .put("createdAt", Instant.now().toString())
            .put("version", client.version())
            .put("containsSecrets", includeSecrets);
    ArrayNode entries = out.putArray("resources");
    for (GatewayResource r : GatewayResource.values()) {
      if (r == GatewayResource.CREDENTIALS) continue;
      Listing listing = resources(r.id, null);
      for (Item item : listing.items) {
        addExport(entries, r, item, null, includeSecrets);
        if (r == GatewayResource.CONSUMERS)
          for (Item credential : resources("credentials", item.id).items)
            addExport(entries, GatewayResource.CREDENTIALS, credential, item.id, includeSecrets);
      }
    }
    out.set("runtime", runtime());
    long id =
        audit.beginObject(
            actor,
            "gateway",
            "EXPORT",
            "gateway-config",
            "GATEWAY",
            null,
            Map.of("resources", entries.size(), "includesSensitiveFields", includeSecrets));
    audit.finish(id, "SUCCESS", "网关配置已导出");
    return out;
  }

  private void addExport(
      ArrayNode entries, GatewayResource r, Item item, String parent, boolean includeSecrets) {
    ObjectNode entry = entries.addObject().put("kind", r.id).put("id", item.id);
    if (parent != null) entry.put("parent", parent);
    entry.set("value", includeSecrets ? read(r, item.id, parent).path("value") : item.value);
  }
}
