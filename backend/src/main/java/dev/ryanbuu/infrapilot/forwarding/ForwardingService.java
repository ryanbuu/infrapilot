package dev.ryanbuu.infrapilot.forwarding;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.audit.AuditService;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties;
import dev.ryanbuu.infrapilot.dns.ZoneRegistry;
import dev.ryanbuu.infrapilot.forwarding.ForwardingProperties.Node;
import dev.ryanbuu.infrapilot.forwarding.ForwardingRules.Rule;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ForwardingService {
  public record Change(
      String revision, String operation, String originalDomain, Rule rule, String backupId) {}

  private final ForwardingProperties properties;
  private final InfraPilotProperties app;
  private final ForwardingClient client;
  private final ZoneRegistry registry;
  private final AuditService audit;
  private final ObjectMapper mapper;

  public ForwardingService(
      ForwardingProperties p,
      InfraPilotProperties app,
      ForwardingClient client,
      ZoneRegistry registry,
      AuditService audit,
      ObjectMapper mapper) {
    this.properties = p;
    this.app = app;
    this.client = client;
    this.registry = registry;
    this.audit = audit;
    this.mapper = mapper;
  }

  private List<Node> nodes() {
    if (app.getMode().equals("demo"))
      return List.of(
          new Node("bind1", "BIND 1", "192.0.2.10", 22, "root"),
          new Node("bind2", "BIND 2", "192.0.2.11", 22, "root"));
    List<Node> nodes = properties.getNodes();
    if (!properties.isEnabled()) throw new ApiException(SERVICE_UNAVAILABLE, "条件转发尚未接入");
    if (nodes.size() != 2 || nodes.get(0).id().equals(nodes.get(1).id()))
      throw new ApiException(SERVICE_UNAVAILABLE, "条件转发必须配置两个独立节点");
    return nodes;
  }

  private ObjectNode request(String operation, String tx) {
    ObjectNode q = mapper.createObjectNode().put("operation", operation);
    if (tx != null) q.put("transaction", tx);
    return q;
  }

  private List<Rule> rules(JsonNode value) {
    return ForwardingRules.validate(mapper.convertValue(value, new TypeReference<List<Rule>>() {}));
  }

  private List<ObjectNode> snapshots(List<Node> nodes) {
    List<ObjectNode> result = new ArrayList<>();
    for (Node node : nodes) {
      ObjectNode state = mapper.createObjectNode().put("id", node.id()).put("label", node.label());
      try {
        ObjectNode value = client.call(node, request("read", null));
        value.set("rules", mapper.valueToTree(rules(value.get("rules"))));
        state.setAll(value);
        state.put("online", true);
      } catch (ApiException e) {
        state.put("online", false).put("error", e.getMessage());
      }
      result.add(state);
    }
    return result;
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

  private boolean aligned(List<ObjectNode> states) {
    return states.size() == 2
        && states.stream()
            .allMatch(s -> s.path("online").asBoolean() && !s.path("drift").asBoolean())
        && states.get(0).path("rules").equals(states.get(1).path("rules"));
  }

  public synchronized ObjectNode view() {
    if (!app.getMode().equals("demo") && !properties.isEnabled()) {
      ObjectNode disabled = mapper.createObjectNode().put("enabled", false).put("writable", false);
      disabled.putArray("rules");
      disabled.putArray("nodes");
      return disabled;
    }
    List<Node> nodes = nodes();
    String recovery = recover(nodes);
    List<ObjectNode> states = snapshots(nodes);
    boolean consistent = aligned(states),
        pending = states.stream().anyMatch(s -> s.path("pending").isObject());
    ObjectNode out =
        mapper
            .createObjectNode()
            .put("enabled", true)
            .put("consistent", consistent)
            .put("writable", consistent && !pending && recovery.isEmpty())
            .put("revision", revision(states));
    out.set("nodes", mapper.valueToTree(states));
    JsonNode available =
        states.stream()
            .filter(s -> s.path("online").asBoolean())
            .findFirst()
            .map(s -> s.get("rules"))
            .orElse(mapper.createArrayNode());
    out.set("rules", available.deepCopy());
    if (!recovery.isEmpty()) out.put("error", recovery);
    else if (pending) out.put("error", "节点存在待恢复事务，请检查变更备份后重试恢复");
    else if (!consistent) out.put("error", "节点失联或配置不一致，已暂停保存");
    return out;
  }

  public synchronized ObjectNode change(Change change, String actor, boolean preview) {
    List<Node> nodes = nodes();
    if (!recover(nodes).isEmpty()) throw new ApiException(CONFLICT, "存在未完成事务，请先重试恢复");
    List<ObjectNode> states = snapshots(nodes);
    if (!aligned(states) || states.stream().anyMatch(s -> s.path("pending").isObject()))
      throw new ApiException(CONFLICT, "两台节点必须在线、配置一致且无待结束事务");
    if (change == null || !revision(states).equals(change.revision()))
      throw new ApiException(CONFLICT, "配置版本已变化，请刷新后重新编辑");
    List<Rule> before = rules(states.get(0).get("rules")), next = new ArrayList<>(before);
    String operation = Objects.toString(change.operation(), ""), name;
    if (operation.equals("restore")) {
      ObjectNode backup = readBackup(change.backupId());
      next = new ArrayList<>(rules(backup.get("before")));
      name = "恢复配置";
    } else {
      if (!Set.of("create", "update", "delete").contains(operation))
        throw new ApiException(BAD_REQUEST, "操作无效");
      String original =
          operation.equals("create") ? "" : ForwardingRules.domain(change.originalDomain());
      if (!operation.equals("create") && !next.removeIf(r -> r.domain().equals(original)))
        throw new ApiException(CONFLICT, "规则已不存在，请刷新");
      if (!operation.equals("delete")) {
        Rule rule = ForwardingRules.validate(Collections.singletonList(change.rule())).get(0);
        next.add(rule);
        name = rule.domain();
      } else name = original;
    }
    List<Rule> after = ForwardingRules.validate(next);
    ForwardingRules.protect(after, registry.all().stream().map(z -> z.name()).toList());
    if (before.equals(after)) throw new ApiException(BAD_REQUEST, "配置没有变化");
    for (int i = 0; i < nodes.size(); i++) {
      ObjectNode q =
          request("validate", null).put("revision", states.get(i).path("revision").asText());
      q.set("rules", mapper.valueToTree(after));
      client.call(nodes.get(i), q);
    }
    ObjectNode result = mapper.createObjectNode().put("valid", true).put("nodesValidated", 2);
    result.set("before", mapper.valueToTree(before));
    result.set("after", mapper.valueToTree(after));
    if (preview) return result;
    String id = UUID.randomUUID().toString();
    long auditId =
        audit.beginObject(
            actor,
            "conditional-forwarding",
            operation.equals("restore") ? "RESTORE" : operation.toUpperCase(Locale.ROOT),
            name,
            "FORWARDING",
            before,
            after);
    ObjectNode journal =
        mapper
            .createObjectNode()
            .put("id", id)
            .put("actor", actor)
            .put("operation", operation)
            .put("domain", name)
            .put("createdAt", Instant.now().toString())
            .put("auditId", auditId)
            .put("phase", "preparing");
    journal.set("before", mapper.valueToTree(before));
    journal.set("after", mapper.valueToTree(after));
    persist(journal); // Durable intent precedes the first remote mutation.
    try {
      for (int i = 0; i < nodes.size(); i++) {
        ObjectNode q =
            request("prepare", id).put("revision", states.get(i).path("revision").asText());
        q.set("rules", mapper.valueToTree(after));
        client.call(nodes.get(i), q);
      }
      journal.put("phase", "applying");
      persist(journal);
      for (Node node : nodes) client.call(node, request("commit", id));
      List<ObjectNode> applied = snapshots(nodes);
      if (!aligned(applied) || !rules(applied.get(0).get("rules")).equals(after))
        throw new ApiException(BAD_GATEWAY, "提交后回读不一致");
      journal.put("phase", "committed");
      persist(journal);
    } catch (RuntimeException e) {
      journal.put("phase", "rollback-needed");
      persist(journal);
      boolean restored = settle(nodes, journal, false);
      if (!restored) throw new ApiException(BAD_GATEWAY, "无法确认双节点已恢复；备份已保留，暂停新变更，请重试恢复");
      throw new ApiException(BAD_GATEWAY, "变更未完成，两台节点已恢复原配置");
    }
    if (!settle(nodes, journal, true))
      throw new ApiException(BAD_GATEWAY, "配置已提交，事务收尾待确认；暂停新变更，请重试恢复");
    return result.put("backupId", id).put("status", "SUCCESS");
  }

  private boolean settle(List<Node> nodes, ObjectNode journal, boolean commit) {
    boolean success = true;
    for (Node node : nodes)
      try {
        client.call(node, request(commit ? "finalize" : "abort", journal.path("id").asText()));
      } catch (RuntimeException e) {
        success = false;
      }
    if (success) {
      List<ObjectNode> states = snapshots(nodes);
      success =
          aligned(states)
              && states.stream().noneMatch(s -> s.path("pending").isObject())
              && rules(states.get(0).get("rules"))
                  .equals(rules(journal.get(commit ? "after" : "before")));
    }
    if (success) {
      // If the DB is unavailable the durable journal remains recoverable; no false completion.
      audit.finish(
          journal.path("auditId").asLong(),
          commit ? "SUCCESS" : "FAILED",
          commit ? "两台 BIND 已校验、重载并回读一致；备份 " + journal.path("id").asText() : "变更失败，两台 BIND 已恢复原配置");
      journal.put("phase", commit ? "complete" : "rolled-back");
      persist(journal);
    }
    return success;
  }

  private String recover(List<Node> nodes) {
    for (ObjectNode j : journals()) {
      String phase = j.path("phase").asText();
      if (Set.of("complete", "rolled-back").contains(phase)) continue;
      try {
        if (!settle(nodes, j, phase.equals("committed"))) return "存在未完成事务，已暂停保存；请重试恢复";
      } catch (RuntimeException e) {
        return "恢复结果尚未确认，已暂停保存；请检查节点与审计数据库";
      }
    }
    return "";
  }

  public synchronized ObjectNode retryRecovery() {
    String error = recover(nodes());
    if (!error.isEmpty()) throw new ApiException(BAD_GATEWAY, error);
    return view();
  }

  public synchronized List<ObjectNode> backups() {
    List<ObjectNode> out = journals();
    Collections.reverse(out);
    return out;
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
      throw new ApiException(NOT_FOUND, "备份不存在或无法读取");
    }
  }

  private List<ObjectNode> journals() {
    if (!Files.exists(directory())) return new ArrayList<>();
    try (var files = Files.list(directory())) {
      List<ObjectNode> list = new ArrayList<>();
      for (Path f :
          files
              .filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
              .sorted()
              .toList()) list.add((ObjectNode) mapper.readTree(Files.readAllBytes(f)));
      list.sort(Comparator.comparing(j -> j.path("createdAt").asText()));
      return list;
    } catch (Exception e) {
      throw new ApiException(SERVICE_UNAVAILABLE, "无法读取条件转发事务备份，已停止操作");
    }
  }

  private void persist(ObjectNode journal) {
    Path temp = null;
    try {
      Files.createDirectories(directory());
      Files.setPosixFilePermissions(directory(), PosixFilePermissions.fromString("rwx------"));
      temp = Files.createTempFile(directory(), ".transaction-", ".tmp");
      Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
      Files.write(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(journal));
      try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Files.move(
          temp,
          file(journal.path("id").asText()),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      try (FileChannel channel = FileChannel.open(directory(), StandardOpenOption.READ)) {
        channel.force(true);
      }
    } catch (Exception e) {
      throw new ApiException(SERVICE_UNAVAILABLE, "无法持久化条件转发备份，已停止操作");
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (Exception ignored) {
        }
    }
  }
}
