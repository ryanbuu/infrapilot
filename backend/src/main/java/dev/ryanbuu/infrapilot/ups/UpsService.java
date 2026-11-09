package dev.ryanbuu.infrapilot.ups;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.audit.AuditService;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class UpsService {
  static final String MASK = "__INFRAPILOT_REDACTED__";
  private final UpsCatalog catalog;
  private final UpsClient client;
  private final UpsProperties properties;
  private final ObjectMapper mapper;
  private final AuditService audit;
  private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

  record Snapshot(String path, JsonNode data) {}

  record Ticket(String actor, Instant expiry, UpsCatalog.Call call, Snapshot before) {}

  public UpsService(
      UpsCatalog catalog,
      UpsClient client,
      UpsProperties properties,
      ObjectMapper mapper,
      AuditService audit) {
    this.catalog = catalog;
    this.client = client;
    this.properties = properties;
    this.mapper = mapper;
    this.audit = audit;
  }

  public JsonNode catalog() {
    ObjectNode c = (ObjectNode) catalog.publicCatalog();
    c.put("enabled", client.enabled());
    c.put("demo", client.demo());
    return c;
  }

  public Object query(String id, JsonNode input, String actor) {
    var call = catalog.resolve(id, input);
    if (!call.operation().path("read").asBoolean()) throw UpsCatalog.bad("写操作必须先预览并确认");
    if (call.path().startsWith("/api/advanced/nut/config/")) {
      long event = audit.beginObject(actor, "ups", "READ_CONFIG", call.path(), "UPS", null, null);
      try {
        var result = result(client.call(call.method(), call.path(), call.body(), false));
        audit.finish(event, "SUCCESS", "配置读取");
        return result;
      } catch (RuntimeException e) {
        audit.finish(event, "FAILED", "配置读取失败");
        throw e;
      }
    }
    return result(
        client.call(
            call.method(), call.path(), call.body(), call.operation().path("binary").asBoolean()));
  }

  public synchronized Object preview(String id, JsonNode input, String actor) {
    var call = catalog.resolve(id, input);
    if (call.operation().path("read").asBoolean()) throw UpsCatalog.bad("查询操作不需要变更预览");
    protectAccount(call);
    Snapshot before = snapshot(call);
    JsonNode original = unwrap(before.data());
    if (call.path().contains("options-tabs") && original.has("options_tabs"))
      original = before.data();
    ObjectNode body = (ObjectNode) restoreSecrets(call.body(), original);
    // Keep permissions for notification pages unchanged; these pages are intentionally outside this
    // integration.
    if (call.path().contains("/options-tabs") && body.path("options_tabs").isObject())
      for (String k : List.of("email", "extranotifs", "webhook"))
        if (before.data().path("options_tabs").has(k))
          ((ObjectNode) body.path("options_tabs"))
              .set(k, before.data().path("options_tabs").get(k));
    call = new UpsCatalog.Call(call.method(), call.path(), body, call.operation(), call.input());
    tickets.entrySet().removeIf(e -> e.getValue().expiry().isBefore(Instant.now()));
    if (tickets.size() > 128) throw UpsCatalog.bad("待确认操作过多，请稍后重试");
    String token = UUID.randomUUID().toString();
    tickets.put(token, new Ticket(actor, Instant.now().plusSeconds(300), call, before));
    return Map.of(
        "token",
        token,
        "expiresIn",
        300,
        "operation",
        call.operation().path("title").asText(),
        "targetId",
        call.input().path("targetId").asInt(),
        "before",
        redact(before.data()),
        "after",
        redact(body),
        "effect",
        effect(call));
  }

  public synchronized Object execute(String token, String actor) {
    Ticket t = tickets.get(token);
    if (t == null || !t.actor().equals(actor) || t.expiry().isBefore(Instant.now()))
      throw new ApiException(HttpStatus.CONFLICT, "预览已过期，请重新校验");
    tickets.remove(token);
    var call = t.call();
    protectAccount(call);
    if (!t.before().path().isBlank()) {
      JsonNode current = client.call("GET", t.before().path(), null, false).data();
      if (!configuration(current).equals(configuration(t.before().data())))
        throw new ApiException(HttpStatus.CONFLICT, "配置在预览后发生变化，请重新打开并校验");
    }
    String backup = backup(t);
    long event =
        audit.beginObject(
            actor,
            "ups",
            call.method(),
            call.operation().path("title").asText(),
            "UPS",
            redact(t.before().data()),
            redact(call.body()));
    try {
      var response = client.call(call.method(), call.path(), call.body(), false);
      audit.finish(event, "SUCCESS", "UPS 操作完成；变更备份 " + backup);
      return result(response);
    } catch (RuntimeException e) {
      audit.finish(event, "FAILED", "UPS 操作未确认成功；请刷新核对，备份 " + backup);
      throw e;
    }
  }

  private String effect(UpsCatalog.Call c) {
    String p = c.path();
    if (p.contains("/execute") || p.contains("/script-actions/") && p.contains("/test"))
      return "立即执行设备命令或脚本；执行结果不能通过配置备份撤销。";
    if (p.contains("restart")
        || p.contains("/stop")
        || p.contains("delete-config")
        || p.contains("save-config")) return "会影响监控服务或接入配置；远程 NUT 服务及宿主机的断电保护配置独立管理。";
    if (c.method().equals("DELETE") || p.contains("clear"))
      return "删除操作；执行前保存当前可读取状态，历史数据恢复需要数据库备份。";
    return "保存前检查当前配置，保留变更备份并记录操作审计。";
  }

  private void protectAccount(UpsCatalog.Call c) {
    if (!c.path().startsWith("/auth/api/admin/users/")) return;
    String id = c.input().path("params").path("user_id").asText();
    JsonNode users = client.call("GET", "/auth/api/admin/users", null, false).data().path("users");
    for (JsonNode user : users)
      if (user.path("id").asText().equals(id)
          && user.path("username").asText().equals(properties.getUsername()))
        throw UpsCatalog.bad("此账号用于服务连接，不能在这里修改；请同步更新服务端连接凭据后操作");
  }

  private Snapshot snapshot(UpsCatalog.Call c) {
    String path = c.operation().path("path").asText(), read = "";
    String concrete = c.path().split("\\?", 2)[0];
    if (catalog.find("GET", path).isPresent()) read = concrete;
    else if (path.contains("/script-actions/")) read = "/api/options/script-actions";
    else if (path.contains("/multi-nut/targets/") && !path.endsWith("/test"))
      read = "/api/multi-nut/targets/" + c.input().path("params").path("target_id").asInt();
    else if (path.contains("/oidc/")) read = "/auth/api/admin/oidc";
    else if (path.contains("/users/")) read = "/auth/api/admin/users";
    else if (path.endsWith("/renamer/mappings")) read = "/api/multi-nut/renamer/catalog";
    else if (path.endsWith("/polling-interval")) read = "/api/settings/variables";
    if (read.isBlank()) return new Snapshot("", mapper.createObjectNode());
    if (c.operation().path("scope").asBoolean())
      read += "?target_id=" + c.input().path("targetId").asInt();
    return new Snapshot(read, client.call("GET", read, null, false).data());
  }

  private JsonNode configuration(JsonNode data) {
    JsonNode value = data.deepCopy();
    if (value.path("data").isObject()) ((ObjectNode) value.path("data")).remove("latest");
    return value;
  }

  private JsonNode unwrap(JsonNode data) {
    for (String k : List.of("data", "target", "config"))
      if (data.path(k).isObject()) return data.path(k);
    return data;
  }

  private JsonNode restoreSecrets(JsonNode node, JsonNode original) {
    if (node.isTextual() && node.asText().equals(MASK)) {
      if (original.isMissingNode() || original.isNull() || original.asText().equals(MASK))
        throw UpsCatalog.bad("无法恢复未读取的密钥，请重新填写");
      return original.deepCopy();
    }
    JsonNode copy = node.deepCopy();
    if (copy.isObject()) {
      var obj = (ObjectNode) copy;
      var names = new ArrayList<String>();
      obj.fieldNames().forEachRemaining(names::add);
      for (String key : names) obj.set(key, restoreSecrets(obj.get(key), original.path(key)));
    } else if (copy.isArray())
      for (int i = 0; i < copy.size(); i++)
        ((ArrayNode) copy).set(i, restoreSecrets(copy.get(i), original.path(i)));
    return copy;
  }

  static JsonNode redact(JsonNode node) {
    if (node == null) return JsonNodeFactory.instance.nullNode();
    JsonNode copy = node.deepCopy();
    if (copy.isObject()) {
      var obj = (ObjectNode) copy;
      var names = new ArrayList<String>();
      obj.fieldNames().forEachRemaining(names::add);
      for (String key : names) {
        if (obj.get(key).isTextual()
            && !obj.get(key).asText().isBlank()
            && key.matches("(?i).*(password|passwd|secret|token|private.key|credential).*"))
          obj.put(key, MASK);
        else if (Set.of("content", "script_body", "upsd_users", "upsmon_conf").contains(key))
          obj.put(key, "[内容仅在编辑器中显示]");
        else obj.set(key, redact(obj.get(key)));
      }
    } else if (copy.isArray())
      for (int i = 0; i < copy.size(); i++) ((ArrayNode) copy).set(i, redact(copy.get(i)));
    return copy;
  }

  // Read-only config editor receives its own explicit file content; other JSON secrets remain
  // masked.
  private Object result(UpsClient.Reply reply) {
    if (reply.binary() != null)
      return Map.of(
          "download",
          Base64.getEncoder().encodeToString(reply.binary()),
          "contentType",
          reply.contentType(),
          "filename",
          reply.contentType().contains("zip") ? "nutify-logs.zip" : "nutify-database-backup.db");
    JsonNode safe = redact(reply.data());
    if (reply.data().path("config").has("content"))
      ((ObjectNode) safe.path("config")).set("content", reply.data().path("config").get("content"));
    // Script bodies are administrator-editable; never copy them to audit or previews.
    exposeScriptBodies(reply.data(), safe);
    return Map.of("data", safe);
  }

  private void exposeScriptBodies(JsonNode raw, JsonNode safe) {
    if (raw.isObject()) {
      raw.fields()
          .forEachRemaining(
              e -> {
                if (e.getKey().equals("script_body"))
                  ((ObjectNode) safe).set(e.getKey(), e.getValue());
                else exposeScriptBodies(e.getValue(), safe.path(e.getKey()));
              });
    } else if (raw.isArray())
      for (int i = 0; i < raw.size(); i++) exposeScriptBodies(raw.get(i), safe.path(i));
  }

  private String backup(Ticket t) {
    try {
      Path dir = Path.of(properties.getBackupDirectory());
      Files.createDirectories(dir);
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
      String id = Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
      Path file = dir.resolve(id + ".json");
      JsonNode value =
          mapper.valueToTree(
              Map.of(
                  "actor",
                  t.actor(),
                  "at",
                  Instant.now().toString(),
                  "operation",
                  t.call().operation().path("id").asText(),
                  "input",
                  t.call().input(),
                  "before",
                  t.before().data(),
                  "beforePath",
                  t.before().path()));
      Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
      return id;
    } catch (Exception e) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "无法写入 UPS 变更备份，操作已取消");
    }
  }
}
