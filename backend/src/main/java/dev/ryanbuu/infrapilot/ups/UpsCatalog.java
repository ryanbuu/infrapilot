package dev.ryanbuu.infrapilot.ups;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.ryanbuu.infrapilot.api.ApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class UpsCatalog {
  private final JsonNode catalog;
  private final Map<String, JsonNode> operations = new LinkedHashMap<>();

  public UpsCatalog(ObjectMapper mapper) throws Exception {
    try (var in = getClass().getResourceAsStream("/ups/catalog.json")) {
      catalog = mapper.readTree(in);
    }
    for (JsonNode op : catalog.path("operations")) {
      String path = op.path("path").asText();
      if (path.matches(
          ".*(?i:ntfy|telegram|webhook|notifycmd|/report/send|/report/schedule|/settings/mail|/settings/nutify|/settings/report|test-notification).*"))
        throw new IllegalStateException("Notification operation is forbidden");
      operations.put(op.path("id").asText(), op);
    }
  }

  public JsonNode publicCatalog() {
    return catalog.deepCopy();
  }

  public JsonNode operation(String id) {
    var op = operations.get(id);
    if (op == null) throw bad("未知 UPS 操作");
    return op;
  }

  public Optional<JsonNode> find(String method, String path) {
    return operations.values().stream()
        .filter(
            o -> o.path("method").asText().equals(method) && o.path("path").asText().equals(path))
        .findFirst();
  }

  public record Call(
      String method, String path, ObjectNode body, JsonNode operation, ObjectNode input) {}

  public Call resolve(String id, JsonNode input) {
    JsonNode op = operation(id);
    if (input != null && !input.isObject()) throw bad("请求必须是字段组");
    ObjectNode v =
        input == null ? JsonNodeFactory.instance.objectNode() : (ObjectNode) input.deepCopy();
    if (!v.isObject() || v.toString().length() > 500_000) throw bad("请求过大或格式不正确");
    for (String part : List.of("params", "query", "body")) {
      if (!v.has(part)) v.putObject(part);
      if (!v.path(part).isObject()) throw bad("参数必须是字段组");
      validate(op.path(part), v.path(part), part);
    }
    String path = op.path("path").asText();
    Matcher m = Pattern.compile("<(?:(int):)?(\\w+)>").matcher(path);
    StringBuffer out = new StringBuffer();
    while (m.find()) {
      String key = m.group(2), value = v.path("params").path(key).asText();
      if (value.isBlank()
          || value.length() > 100
          || !value.matches("[a-zA-Z0-9_.-]+")
          || value.contains("..")
          || m.group(1) != null && !value.matches("[1-9][0-9]*")) throw bad("无效的路径参数：" + key);
      if (key.equals("filename")
          && !Set.of(
                  "nut.conf", "ups.conf", "upsd.conf", "upsd.users", "upsmon.conf", "upssched.conf")
              .contains(value)) throw bad("不支持的配置文件");
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    ObjectNode query = (ObjectNode) v.path("query"), body = (ObjectNode) v.path("body");
    if (query.has("target_id") || body.has("target_id")) throw bad("请通过设备选择器指定目标");
    if (op.path("scope").asBoolean()) {
      int target = v.path("targetId").asInt(0);
      if (target < 1) throw bad("请选择 UPS 设备");
      query.put("target_id", target);
      if (!op.path("method").asText().equals("GET")) body.put("target_id", target);
    }
    var pairs = new ArrayList<String>();
    query
        .fields()
        .forEachRemaining(
            e -> {
              if (!e.getValue().isValueNode()) throw bad("查询参数必须是标量");
              pairs.add(enc(e.getKey()) + "=" + enc(e.getValue().asText()));
            });
    return new Call(
        op.path("method").asText(),
        out + (pairs.isEmpty() ? "" : "?" + String.join("&", pairs)),
        body,
        op,
        v);
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  static ApiException bad(String m) {
    return new ApiException(HttpStatus.BAD_REQUEST, m);
  }

  static void validate(JsonNode s, JsonNode v, String path) {
    if (v.isNull()) return;
    String type = s.path("type").asText();
    boolean valid =
        switch (type) {
          case "object" -> v.isObject();
          case "array" -> v.isArray();
          case "integer" -> v.isIntegralNumber();
          case "number" -> v.isNumber();
          case "boolean" -> v.isBoolean();
          case "string" -> v.isTextual();
          default -> true;
        };
    if (!valid) throw bad(path + " 类型不正确");
    if (v.isObject()) {
      for (JsonNode req : s.path("required"))
        if (!v.hasNonNull(req.asText())
            || v.path(req.asText()).asText().isEmpty() && v.path(req.asText()).isTextual())
          throw bad("缺少 " + req.asText());
      v.fields()
          .forEachRemaining(
              e -> {
                var child = s.path("properties").path(e.getKey());
                if (child.isMissingNode()
                    && s.path("additionalProperties").isBoolean()
                    && !s.path("additionalProperties").asBoolean())
                  throw bad("不支持的字段 " + e.getKey());
                validate(child, e.getValue(), path + "." + e.getKey());
              });
    } else if (v.isArray()) {
      if (v.size() > 1000) throw bad("列表过长");
      v.forEach(x -> validate(s.path("items"), x, path));
    } else if (v.isNumber()) {
      if (s.has("minimum") && v.asDouble() < s.path("minimum").asDouble()
          || s.has("maximum") && v.asDouble() > s.path("maximum").asDouble())
        throw bad(path + " 超出范围");
    } else if (v.isTextual() && v.asText().length() > 300_000) throw bad("字段过长");
    if (s.path("enum").isArray()
        && !java.util.stream.StreamSupport.stream(s.path("enum").spliterator(), false)
            .anyMatch(v::equals)) throw bad(path + " 选项无效");
  }
}
