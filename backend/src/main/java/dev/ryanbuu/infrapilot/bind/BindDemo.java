package dev.ryanbuu.infrapilot.bind;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.ryanbuu.infrapilot.api.ApiException;
import java.util.*;
import java.util.regex.*;

/** Stateful sample transport. Production always uses the isolated node adapter. */
final class BindDemo {
  private final ObjectMapper mapper;
  private final Map<String, ObjectNode> states = new HashMap<>(), transactions = new HashMap<>();

  BindDemo(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  synchronized ObjectNode call(BindProperties.Node node, ObjectNode q) {
    ObjectNode state = states.computeIfAbsent(node.id(), id -> initial(node));
    String op = q.path("operation").asText(),
        tx = q.path("transaction").asText(),
        key = node.id() + tx;
    if (op.equals("read")) return state.deepCopy();
    if (op.equals("telemetry")) {
      ObjectNode r =
          mapper
              .createObjectNode()
              .put("status", "server is up and running")
              .put("logs", "2026-10-21T12:00:00Z named: running");
      r.set(
          "statistics",
          mapper.valueToTree(
              Map.of(
                  "nsstats",
                  Map.of("Requestv4", 14239, "QrySuccess", 13802),
                  "views",
                  Map.of(
                      "_default", Map.of("resolver", Map.of("stats", Map.of("Queryv4", 2021)))))));
      return r;
    }
    if (op.equals("command"))
      return mapper.createObjectNode().put("output", "命令已完成：" + q.path("command").asText());
    if (Set.of("plan", "validate", "prepare").contains(op)) {
      if (!q.path("revision").equals(state.path("revision")) || state.path("pending").isObject())
        throw new ApiException(CONFLICT, "配置版本已变化或存在待完成事务");
      if (op.equals("plan")) return plan(state, q.path("action"));
      ArrayNode files = (ArrayNode) q.path("files");
      if (files.isEmpty()) throw new ApiException(BAD_REQUEST, "配置没有变化");
      ObjectNode out =
          mapper
              .createObjectNode()
              .put("valid", true)
              .put("digest", files.toString().hashCode() + "");
      ArrayNode before = mapper.createArrayNode();
      for (JsonNode f : files) {
        String content =
            state.path("files").findValues("path").contains(f.path("path"))
                ? state.path("files").get(0).path("content").asText()
                : "";
        before.addObject().put("path", f.path("path").asText()).put("content", content);
      }
      out.set("before", before);
      out.set("after", files.deepCopy());
      if (op.equals("validate")) return out;
      ObjectNode record = mapper.createObjectNode().put("status", "prepared");
      record.set("before", state.deepCopy());
      record.set("files", files.deepCopy());
      transactions.put(key, record);
      state.set("pending", mapper.createObjectNode().put("id", tx));
      return mapper.createObjectNode().put("status", "prepared");
    }
    ObjectNode r = transactions.get(key);
    if (r == null && op.equals("abort"))
      return mapper.createObjectNode().put("status", "not-prepared");
    if (r == null) throw new ApiException(CONFLICT, "事务不存在");
    switch (op) {
      case "commit" -> {
        ArrayNode files = (ArrayNode) r.get("files").deepCopy();
        files.forEach(f -> ((ObjectNode) f).put("editable", true));
        state.set("files", files);
        refresh(state);
        state.put("revision", UUID.randomUUID().toString());
        r.put("status", "applied");
      }
      case "abort" -> {
        states.put(node.id(), (ObjectNode) r.get("before").deepCopy());
        r.put("status", "rolled-back");
      }
      case "finalize" -> {
        state.putNull("pending");
        r.put("status", "complete");
      }
      case "status" -> {}
      default -> throw new ApiException(BAD_REQUEST, "不支持的操作");
    }
    return mapper.createObjectNode().put("status", r.path("status").asText());
  }

  private ObjectNode initial(BindProperties.Node n) {
    ObjectNode s =
        mapper
            .createObjectNode()
            .put("node", n.id())
            .put("address", n.host())
            .put("revision", UUID.randomUUID().toString())
            .put("managedDirectory", "/etc/bind/infrapilot-manager")
            .put("zoneDirectory", "/var/lib/bind/infrapilot")
            .put("primaryView", "_default")
            .put("status", "server is up and running");
    s.putNull("pending");
    s.putArray("views").add("_default");
    s.putArray("protectedZones").add("intra.example.com");
    s.putArray("version").add("BIND 9.20.29 (demo)");
    s.putArray("files")
        .addObject()
        .put("path", "/etc/bind/named.conf")
        .put("editable", true)
        .put(
            "content",
            "acl trusted { 192.0.2.0/24; localhost; };\n"
                + "options { directory \"/var/cache/bind\"; recursion yes; dnssec-validation auto;"
                + " allow-recursion { trusted; }; };\n"
                + "logging { category default { default_syslog; }; };\n"
                + "dnssec-policy internal { keys { csk lifetime unlimited algorithm"
                + " ecdsap256sha256; }; };\n"
                + "zone \"intra.example.com\" { type "
                + (n.id().equals("bind1") ? "primary" : "secondary")
                + "; file \"/var/lib/bind/intra.zone\"; };\n");
    s.set("commands", BindService.demoCommands(mapper));
    refresh(s);
    return s;
  }

  private ObjectNode plan(ObjectNode s, JsonNode action) {
    String op = action.path("operation").asText(),
        source = s.path("files").get(0).path("content").asText();
    if (op.equals("create")) source += "\n" + action.path("source").asText() + "\n";
    else if (op.equals("zone-create"))
      source += "\nzone \"" + action.path("name").asText() + "\" { type primary; };\n";
    else if (op.equals("restore")) {
      ObjectNode r = transactions.get(s.path("node").asText() + action.path("backupId").asText());
      if (r == null) throw new ApiException(NOT_FOUND, "备份不存在");
      source = r.path("before").path("files").get(0).path("content").asText();
    } else {
      JsonNode object = null;
      for (JsonNode o : s.path("objects"))
        if (o.path("id").asText().equals(action.path("id").asText())
            || op.equals("zone-delete")
                && o.path("kind").asText().equals("zone")
                && o.path("name").equals(action.path("name"))) object = o;
      if (object == null) throw new ApiException(CONFLICT, "配置对象不存在");
      source =
          source.substring(0, object.path("start").asInt())
              + (op.equals("update") ? action.path("source").asText() : "")
              + source.substring(object.path("end").asInt());
    }
    ObjectNode out = mapper.createObjectNode();
    out.putArray("files").addObject().put("path", "/etc/bind/named.conf").put("content", source);
    out.putArray("assets");
    return out;
  }

  private void refresh(ObjectNode s) {
    ArrayNode objects = s.putArray("objects");
    for (JsonNode file : s.path("files")) {
      String text = file.path("content").asText();
      Matcher m =
          Pattern.compile("(?m)([a-z][a-z-]*)(?:\\s+\"([^\"]+)\"|\\s+([a-zA-Z0-9_.-]+))?\\s*\\{")
              .matcher(text);
      int offset = 0;
      while (m.find(offset)) {
        int depth = 1, end = m.end();
        boolean quote = false;
        for (; end < text.length() && depth > 0; end++) {
          char c = text.charAt(end);
          if (c == '"' && (end == 0 || text.charAt(end - 1) != '\\')) quote = !quote;
          if (!quote) {
            if (c == '{') depth++;
            if (c == '}') depth--;
          }
        }
        while (end < text.length() && Character.isWhitespace(text.charAt(end))) end++;
        if (end < text.length() && text.charAt(end) == ';') end++;
        String kind = m.group(1),
            name = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : kind;
        ObjectNode o =
            objects
                .addObject()
                .put("id", kind + ":" + name)
                .put("kind", kind)
                .put("name", name)
                .put("view", "_default")
                .put("file", file.path("path").asText())
                .put("source", text.substring(m.start(), end))
                .put("start", m.start())
                .put("end", end)
                .put("editable", true);
        o.putObject("fields")
            .put("type", s.path("node").asText().equals("bind1") ? "primary" : "secondary");
        offset = end;
      }
    }
    s.put("effective", s.path("files").get(0).path("content").asText());
  }
}
