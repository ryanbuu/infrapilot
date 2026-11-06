package dev.ryanbuu.infrapilot.bind;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.audit.AuditService;
import dev.ryanbuu.infrapilot.bind.BindProperties.Node;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties.*;
import dev.ryanbuu.infrapilot.dns.ZoneRegistry;
import dev.ryanbuu.infrapilot.forwarding.ForwardingRules;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class BindService {
  public record Change(String revision, ObjectNode action, List<ObjectNode> nodes) {}

  public record Command(
      String revision,
      List<String> nodes,
      String command,
      ObjectNode arguments,
      boolean confirmed) {}

  private final BindProperties properties;
  private final InfraPilotProperties app;
  private final BindClient client;
  private final AuditService audit;
  private final ObjectMapper mapper;
  private final ZoneRegistry registry;

  public BindService(
      BindProperties p,
      InfraPilotProperties app,
      BindClient client,
      AuditService audit,
      ObjectMapper mapper,
      ZoneRegistry registry) {
    this.properties = p;
    this.app = app;
    this.client = client;
    this.audit = audit;
    this.mapper = mapper;
    this.registry = registry;
    loadCatalog();
  }

  private List<Node> nodes() {
    if (app.getMode().equals("demo"))
      return List.of(
          new Node("bind1", "BIND 1", "192.0.2.10", 22, "root"),
          new Node("bind2", "BIND 2", "192.0.2.11", 22, "root"));
    if (!properties.isEnabled()) throw new ApiException(SERVICE_UNAVAILABLE, "BIND 管理尚未接入");
    if (properties.getNodes().size() != 2
        || properties.getNodes().get(0).id().equals(properties.getNodes().get(1).id()))
      throw new ApiException(SERVICE_UNAVAILABLE, "必须配置两个独立 DNS 节点");
    return properties.getNodes();
  }

  private ObjectNode q(String op, String tx) {
    ObjectNode q = mapper.createObjectNode().put("operation", op);
    if (tx != null) q.put("transaction", tx);
    return q;
  }

  private List<ObjectNode> snapshots(List<Node> nodes) {
    List<ObjectNode> states = new ArrayList<>();
    for (Node node : nodes) {
      ObjectNode s = mapper.createObjectNode().put("id", node.id()).put("label", node.label());
      try {
        s.setAll(client.call(node, q("read", null)));
        s.put("online", true);
      } catch (ApiException e) {
        s.put("online", false).put("error", e.getMessage());
      }
      states.add(s);
    }
    return states;
  }

  private String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String revision(List<ObjectNode> states) {
    return hash(
        states.stream()
            .map(s -> List.of(s.path("id").asText(), s.path("revision").asText()))
            .toList());
  }

  private boolean ready(List<ObjectNode> states) {
    return states.size() == 2
        && states.stream()
            .allMatch(s -> s.path("online").asBoolean() && !s.path("pending").isObject());
  }

  public synchronized ObjectNode view() {
    if (!app.getMode().equals("demo") && !properties.isEnabled())
      return mapper.createObjectNode().put("enabled", false).put("writable", false);
    List<Node> ns = nodes();
    String error = recover(ns);
    List<ObjectNode> states = snapshots(ns);
    ObjectNode out =
        mapper
            .createObjectNode()
            .put("enabled", true)
            .put("writable", ready(states) && error.isEmpty())
            .put("revision", revision(states));
    out.set("nodes", mapper.valueToTree(states));
    out.set("sections", sections(mapper));
    if (!error.isEmpty()) out.put("error", error);
    else if (!ready(states)) out.put("error", "节点失联或存在待完成事务，已暂停保存");
    return out;
  }

  public synchronized ObjectNode change(Change change, String actor, boolean preview) {
    List<Node> ns = nodes();
    if (!recover(ns).isEmpty()) throw new ApiException(CONFLICT, "存在未完成事务，请先恢复");
    List<ObjectNode> states = snapshots(ns);
    if (!ready(states)) throw new ApiException(CONFLICT, "两台节点须在线且无待结束事务");
    if (change == null || !revision(states).equals(change.revision()))
      throw new ApiException(CONFLICT, "配置版本已变化，请刷新后重新编辑");
    List<ObjectNode> plans = new ArrayList<>(), validated = new ArrayList<>();
    ObjectNode action = change.action() == null ? null : change.action().deepCopy();
    if (action != null && !action.isEmpty()) {
      if (change.nodes() != null && !change.nodes().isEmpty())
        throw new ApiException(BAD_REQUEST, "配置操作与原生文件不能同时提交");
      String operation = action.path("operation").asText();
      if (Set.of("zone-create", "zone-delete").contains(operation))
        action.put("name", ForwardingRules.domain(action.path("name").asText()));
      if (!Set.of("create", "update", "delete", "zone-create", "zone-delete", "restore")
          .contains(operation)) throw new ApiException(BAD_REQUEST, "配置操作无效");
      if (operation.equals("restore")) readBackup(action.path("backupId").asText());
      for (int i = 0; i < ns.size(); i++) {
        // An object id belongs to one specific node. Common configuration changes
        // identify the peer by kind/name/view, never by offset or array position.
        ObjectNode target = action.deepCopy();
        if (operation.equals("restore")
            && !participants(readBackup(action.path("backupId").asText()), ns)
                .contains(ns.get(i))) {
          plans.add(null);
          validated.add(null);
          continue;
        }
        if (action.has("node")
            && !action.path("node").asText().equals(ns.get(i).id())
            && !action.path("both").asBoolean()) {
          plans.add(null);
          validated.add(null);
          continue;
        }
        if (action.path("both").asBoolean()
            && action.has("id")
            && !action.path("node").asText().equals(ns.get(i).id())) {
          JsonNode match = null;
          for (JsonNode o : states.get(i).path("objects"))
            if (o.path("kind").equals(action.path("kind"))
                && o.path("name").equals(action.path("name"))
                && o.path("view").equals(action.path("view"))) match = o;
          if (match == null) throw new ApiException(CONFLICT, "另一节点没有对应配置对象，请分别编辑");
          target.put("id", match.path("id").asText());
        }
        ObjectNode request =
            q("plan", null).put("revision", states.get(i).path("revision").asText());
        request.set("action", target);
        plans.add(client.call(ns.get(i), request));
        validated.add(null);
      }
    } else {
      if (change.nodes() == null || change.nodes().isEmpty() || change.nodes().size() > 2)
        throw new ApiException(BAD_REQUEST, "请选择要修改的节点");
      Set<String> ids = new HashSet<>();
      for (ObjectNode n : change.nodes())
        if (!ids.add(n.path("id").asText())
            || ns.stream().noneMatch(v -> v.id().equals(n.path("id").asText())))
          throw new ApiException(BAD_REQUEST, "节点无效或重复");
      for (Node n : ns) {
        plans.add(
            change.nodes().stream()
                .filter(v -> v.path("id").asText().equals(n.id()))
                .findFirst()
                .orElse(null));
        validated.add(null);
      }
    }
    ArrayNode before = mapper.createArrayNode(), after = mapper.createArrayNode();
    for (int i = 0; i < ns.size(); i++) {
      if (plans.get(i) == null) continue;
      ObjectNode request =
          q("validate", null).put("revision", states.get(i).path("revision").asText());
      request.set("files", plans.get(i).path("files"));
      request.set("assets", plans.get(i).path("assets"));
      if (plans.get(i).has("restoreFrom"))
        request.set("restoreFrom", plans.get(i).get("restoreFrom"));
      ObjectNode result = client.call(ns.get(i), request);
      validated.set(i, result);
      ObjectNode b = before.addObject().put("id", ns.get(i).id()),
          a = after.addObject().put("id", ns.get(i).id());
      b.set("files", result.path("before"));
      a.set("files", result.path("after"));
      a.put("digest", result.path("digest").asText());
    }
    boolean changed = false;
    for (JsonNode n : after) if (!n.path("files").isEmpty()) changed = true;
    if (!changed) throw new ApiException(BAD_REQUEST, "配置没有变化");
    ObjectNode result =
        mapper.createObjectNode().put("valid", true).put("nodesValidated", after.size());
    result.set("before", before);
    result.set("after", after);
    if (preview) return result;
    String id = UUID.randomUUID().toString(),
        operation =
            action == null
                ? "CONFIGURE"
                : action.path("operation").asText().toUpperCase(Locale.ROOT);
    String label =
        action == null ? "原生配置" : action.path("name").asText(action.path("kind").asText("BIND 配置"));
    long auditId = audit.beginObject(actor, "bind", operation, label, "BIND", before, after);
    ObjectNode journal =
        mapper
            .createObjectNode()
            .put("id", id)
            .put("createdAt", Instant.now().toString())
            .put("actor", actor)
            .put("operation", operation)
            .put("name", label)
            .put("auditId", auditId)
            .put("phase", "preparing");
    journal.set("before", before);
    journal.set("after", after);
    List<Zone> catalogBefore = registry.managed(),
        catalogAfter = nextCatalog(action, catalogBefore, ns);
    journal.set("catalogBefore", mapper.valueToTree(catalogBefore));
    journal.set("catalogAfter", mapper.valueToTree(catalogAfter));
    persist(journal);
    try {
      for (int i = 0; i < ns.size(); i++) {
        if (plans.get(i) == null) continue;
        ObjectNode request =
            q("prepare", id).put("revision", states.get(i).path("revision").asText());
        request.set("files", plans.get(i).path("files"));
        request.set("assets", plans.get(i).path("assets"));
        if (plans.get(i).has("restoreFrom"))
          request.set("restoreFrom", plans.get(i).get("restoreFrom"));
        client.call(ns.get(i), request);
      }
      journal.put("phase", "applying");
      persist(journal);
      for (Node n : participants(journal, ns)) client.call(n, q("commit", id));
      // The root adapter verifies file hashes on idempotent commit and finalize.
      // A fresh read also confirms named-checkconf/rndc status on each participant.
      for (Node n : participants(journal, ns)) {
        ObjectNode s = client.call(n, q("read", null));
        if (!s.path("pending").path("id").asText().equals(id))
          throw new ApiException(BAD_GATEWAY, "提交后事务回读失败");
      }
      journal.put("phase", "committed");
      persist(journal);
    } catch (RuntimeException e) {
      journal.put("phase", "rollback-needed");
      persist(journal);
      if (!settle(journal, ns, false)) throw new ApiException(BAD_GATEWAY, "回滚待确认，已暂停保存；节点备份已保留");
      throw new ApiException(BAD_GATEWAY, "发布失败，配置已恢复，备份已保留");
    }
    if (!settle(journal, ns, true)) throw new ApiException(BAD_GATEWAY, "已提交，事务收尾待确认；请重试恢复");
    return result.put("backupId", id).put("status", "SUCCESS");
  }

  private List<Node> participants(ObjectNode j, List<Node> nodes) {
    Set<String> ids = new HashSet<>();
    j.path("after").forEach(v -> ids.add(v.path("id").asText()));
    return nodes.stream().filter(n -> ids.contains(n.id())).toList();
  }

  private boolean settle(ObjectNode journal, List<Node> ns, boolean commit) {
    boolean success = true;
    for (Node n : participants(journal, ns))
      try {
        client.call(n, q(commit ? "finalize" : "abort", journal.path("id").asText()));
      } catch (RuntimeException e) {
        success = false;
      }
    if (!success) return false;
    try {
      List<Zone> catalog =
          mapper.convertValue(
              journal.path(commit ? "catalogAfter" : "catalogBefore"),
              new TypeReference<List<Zone>>() {});
      atomic(directory().resolve("zones.json"), mapper.writeValueAsBytes(catalog));
      registry.setManaged(catalog);
      audit.finish(
          journal.path("auditId").asLong(),
          commit ? "SUCCESS" : "FAILED",
          (commit ? "BIND 已校验并重载；备份 " : "变更失败，配置已恢复；备份 ") + journal.path("id").asText());
      journal.put("phase", commit ? "complete" : "rolled-back");
      persist(journal);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private String recover(List<Node> ns) {
    for (ObjectNode j : journals())
      if (!Set.of("complete", "rolled-back").contains(j.path("phase").asText())) {
        if (!settle(j, ns, j.path("phase").asText().equals("committed")))
          return "存在未完成变更，已暂停保存；请检查节点后重试恢复";
      }
    return "";
  }

  public synchronized ObjectNode retryRecovery() {
    String e = recover(nodes());
    if (!e.isEmpty()) throw new ApiException(BAD_GATEWAY, e);
    return view();
  }

  public synchronized List<ObjectNode> backups() {
    List<ObjectNode> list = journals();
    Collections.reverse(list);
    return list;
  }

  public synchronized ObjectNode telemetry(String node) {
    return client.call(find(node), q("telemetry", null));
  }

  private Node find(String id) {
    return nodes().stream()
        .filter(n -> n.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new ApiException(NOT_FOUND, "节点不存在"));
  }

  public synchronized ObjectNode command(Command c, String actor) {
    if (c == null
        || c.nodes() == null
        || c.nodes().isEmpty()
        || c.nodes().size() > 2
        || new HashSet<>(c.nodes()).size() != c.nodes().size())
      throw new ApiException(BAD_REQUEST, "请选择节点");
    List<Node> ns = nodes();
    if (!recover(ns).isEmpty()) throw new ApiException(CONFLICT, "存在待恢复事务");
    List<ObjectNode> states = snapshots(ns);
    if (!ready(states) || !revision(states).equals(c.revision()))
      throw new ApiException(CONFLICT, "节点状态或配置版本已变化，请刷新");
    JsonNode spec = null;
    for (JsonNode cmd : states.get(0).path("commands"))
      if (cmd.path("id").asText().equals(c.command())) spec = cmd;
    if (spec == null) throw new ApiException(BAD_REQUEST, "不支持的命令");
    if (spec.path("mutating").asBoolean() && !c.confirmed())
      throw new ApiException(BAD_REQUEST, "请确认运行操作");
    for (String node : c.nodes()) find(node);
    long auditId =
        audit.beginObject(
            actor,
            "bind",
            "COMMAND",
            c.command(),
            "BIND",
            null,
            Map.of(
                "nodes",
                c.nodes(),
                "command",
                c.command(),
                "arguments",
                c.arguments() == null ? mapper.createObjectNode() : c.arguments()));
    ObjectNode result = mapper.createObjectNode();
    ArrayNode output = result.putArray("nodes");
    boolean success = true;
    for (String id : c.nodes()) {
      ObjectNode r = output.addObject().put("id", id);
      try {
        ObjectNode q =
            q("command", null).put("command", c.command()).put("confirmed", c.confirmed());
        q.set("arguments", c.arguments() == null ? mapper.createObjectNode() : c.arguments());
        r.setAll(client.call(find(id), q));
        r.put("success", true);
      } catch (RuntimeException e) {
        success = false;
        r.put("success", false).put("error", e.getMessage());
      }
    }
    audit.finish(
        auditId, success ? "SUCCESS" : "FAILED", success ? "运行命令已完成" : "部分或全部节点执行失败，请检查逐节点结果");
    return result.put("success", success);
  }

  public ObjectNode generateKey(ObjectNode q) {
    String name = q.path("name").asText(), algorithm = q.path("algorithm").asText("hmac-sha256");
    if (!name.matches("[a-zA-Z0-9_.-]{1,253}")) throw new ApiException(BAD_REQUEST, "密钥名称无效");
    int size =
        switch (algorithm) {
          case "hmac-sha256" -> 32;
          case "hmac-sha384" -> 48;
          case "hmac-sha512" -> 64;
          default -> throw new ApiException(BAD_REQUEST, "算法无效");
        };
    byte[] bytes = new byte[size];
    new SecureRandom().nextBytes(bytes);
    return mapper
        .createObjectNode()
        .put(
            "source",
            "key \""
                + name
                + "\" { algorithm "
                + algorithm
                + "; secret \""
                + Base64.getEncoder().encodeToString(bytes)
                + "\"; };");
  }

  private List<Zone> nextCatalog(ObjectNode action, List<Zone> old, List<Node> ns) {
    if (action == null) return old;
    String op = action.path("operation").asText();
    if (op.equals("restore"))
      return mapper.convertValue(
          readBackup(action.path("backupId").asText()).path("catalogBefore"),
          new TypeReference<List<Zone>>() {});
    List<Zone> result = new ArrayList<>(old);
    String name =
        action.path("name").asText().toLowerCase(Locale.ROOT).replaceAll("\\.$", "") + ".";
    if (op.equals("zone-delete")) result.removeIf(z -> z.name().equals(name));
    if (op.equals("zone-create")) {
      Zone template = registry.all().stream().filter(Zone::writable).findFirst().orElseThrow();
      String id = "bind-" + hash(name).substring(0, 16);
      result.removeIf(z -> z.name().equals(name));
      result.add(
          new Zone(
              id,
              name,
              "权威区域",
              false,
              template.key(),
              new Endpoint(ns.get(0).label(), ns.get(0).host(), 53),
              List.of(new Endpoint(ns.get(1).label(), ns.get(1).host(), 53)),
              List.of()));
    }
    return result;
  }

  private void loadCatalog() {
    Path file = directory().resolve("zones.json");
    if (Files.exists(file))
      try {
        registry.setManaged(
            mapper.readValue(Files.readAllBytes(file), new TypeReference<List<Zone>>() {}));
      } catch (Exception e) {
        throw new IllegalStateException("Unable to load managed DNS catalog", e);
      }
  }

  private Path directory() {
    return Path.of(properties.getBackupDirectory()).toAbsolutePath().normalize();
  }

  private Path file(String id) {
    if (id == null || !id.matches("[a-f0-9-]{36}")) throw new ApiException(BAD_REQUEST, "备份标识无效");
    return directory().resolve(id + ".json");
  }

  private ObjectNode readBackup(String id) {
    try {
      return (ObjectNode) mapper.readTree(Files.readAllBytes(file(id)));
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(NOT_FOUND, "备份不存在");
    }
  }

  private List<ObjectNode> journals() {
    List<ObjectNode> out = new ArrayList<>();
    if (!Files.exists(directory())) return out;
    try (var stream = Files.list(directory())) {
      for (Path p :
          stream.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).toList())
        out.add((ObjectNode) mapper.readTree(Files.readAllBytes(p)));
    } catch (Exception e) {
      throw new ApiException(SERVICE_UNAVAILABLE, "无法读取 BIND 事务备份，已暂停保存");
    }
    out.sort(Comparator.comparing(v -> v.path("createdAt").asText()));
    return out;
  }

  private void persist(ObjectNode j) {
    try {
      atomic(file(j.path("id").asText()), mapper.writeValueAsBytes(j));
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(SERVICE_UNAVAILABLE, "无法持久化 BIND 备份，已停止操作");
    }
  }

  private void atomic(Path path, byte[] data) {
    Path temp = null;
    try {
      Files.createDirectories(directory());
      Files.setPosixFilePermissions(directory(), PosixFilePermissions.fromString("rwx------"));
      temp = Files.createTempFile(directory(), ".bind-", ".tmp");
      Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
      Files.write(temp, data);
      try (FileChannel c = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        c.force(true);
      }
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      try (FileChannel c = FileChannel.open(directory(), StandardOpenOption.READ)) {
        c.force(true);
      }
    } catch (Exception e) {
      throw new ApiException(SERVICE_UNAVAILABLE, "无法持久化 BIND 备份，已停止操作");
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (Exception ignored) {
        }
    }
  }

  static ArrayNode sections(ObjectMapper mapper) {
    return mapper.valueToTree(
        List.of(
            Map.of(
                "id",
                "zones",
                "title",
                "权威区域",
                "kinds",
                List.of("zone", "primaries", "masters", "remote-servers")),
            Map.of("id", "resolver", "title", "递归与缓存", "kinds", List.of("options", "server")),
            Map.of("id", "access", "title", "访问控制", "kinds", List.of("acl", "controls")),
            Map.of("id", "views", "title", "Views", "kinds", List.of("view")),
            Map.of(
                "id",
                "dnssec",
                "title",
                "DNSSEC",
                "kinds",
                List.of(
                    "dnssec-policy",
                    "trust-anchors",
                    "managed-keys",
                    "keystore",
                    "parental-agents")),
            Map.of("id", "keys", "title", "TSIG 密钥", "kinds", List.of("key")),
            Map.of("id", "transport", "title", "加密传输", "kinds", List.of("tls", "http")),
            Map.of(
                "id",
                "logging",
                "title",
                "日志与统计",
                "kinds",
                List.of("logging", "statistics-channels")),
            Map.of(
                "id",
                "advanced",
                "title",
                "原生配置",
                "kinds",
                List.of("dlz", "dyndb", "plugin", "include"))));
  }

  static ArrayNode demoCommands(ObjectMapper mapper) {
    ArrayNode a = mapper.createArrayNode();
    for (String id :
        List.of(
            "status",
            "zone-zonestatus",
            "dnssec-status",
            "flushname",
            "flush",
            "querylog",
            "zone-notify",
            "reload")) {
      ObjectNode c =
          a.addObject()
              .put("id", id)
              .put(
                  "label",
                  switch (id) {
                    case "status" -> "服务状态";
                    case "flushname" -> "清理指定名称缓存";
                    case "flush" -> "清空缓存";
                    default -> id;
                  })
              .put("zone", id.startsWith("zone-") || id.equals("dnssec-status"))
              .put("mutating", !id.contains("status"));
      ArrayNode f = c.putArray("fields");
      if (id.equals("flushname")) f.addObject().put("name", "name").put("type", "domain");
    }
    return a;
  }
}
