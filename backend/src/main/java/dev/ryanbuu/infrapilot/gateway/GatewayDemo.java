package dev.ryanbuu.infrapilot.gateway;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Isolated resources used by the demo and browser tests; never connects to a gateway. */
final class GatewayDemo {
  private final ObjectMapper mapper;
  private final Map<String, ObjectNode> resources = new LinkedHashMap<>();
  private long sequence = 100;
  private boolean initialized;

  GatewayDemo(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  private ObjectNode obj(String text) {
    try {
      return (ObjectNode) mapper.readTree(text);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void seed() {
    if (initialized) return;
    initialized = true;
    put(
        "/upstreams/application",
        obj(
            "{\"id\":\"application\",\"name\":\"内部应用\",\"type\":\"roundrobin\",\"scheme\":\"http\",\"nodes\":{\"192.0.2.20:8080\":1}}"));
    put(
        "/services/console",
        obj(
            "{\"id\":\"console\",\"name\":\"应用控制台\",\"upstream_id\":\"application\",\"plugins\":{\"cors\":{}}}"));
    put(
        "/routes/console",
        obj(
            "{\"id\":\"console\",\"name\":\"应用控制台\",\"hosts\":[\"console.intra.example.com\"],\"uri\":\"/*\",\"service_id\":\"console\",\"status\":1}"));
    put(
        "/consumers/automation",
        obj(
            "{\"username\":\"automation\",\"desc\":\"自动化账户\",\"plugins\":{\"key-auth\":{\"key\":\"demo-secret-do-not-display\"}}}"));
  }

  private ObjectNode put(String path, ObjectNode value) {
    ObjectNode node =
        mapper.createObjectNode().put("key", "/apisix" + path).put("modifiedIndex", ++sequence);
    node.set("value", value.deepCopy());
    resources.put(path, node);
    return node;
  }

  synchronized GatewayClient.Reply request(String method, String path, JsonNode body) {
    seed();
    if (path.equals("/plugins/list"))
      return reply(
          200,
          mapper.valueToTree(
              List.of(
                  "cors",
                  "proxy-rewrite",
                  "response-rewrite",
                  "limit-count",
                  "key-auth",
                  "basic-auth",
                  "jwt-auth",
                  "redirect",
                  "ip-restriction",
                  "prometheus")));
    if (path.startsWith("/schema/validate/")) {
      boolean invalid =
          body == null
              || !body.isObject()
              || body.isEmpty()
              || (body.has("uri") && !body.path("uri").isTextual());
      return reply(
          invalid ? 400 : 200,
          mapper
              .createObjectNode()
              .put(invalid ? "error_msg" : "message", invalid ? "配置字段类型或内容无效" : "ok"));
    }
    if (path.startsWith("/schema/"))
      return reply(
          200,
          obj(
              "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"desc\":{\"type\":\"string\"},\"plugins\":{\"type\":\"object\"}},\"additionalProperties\":true}"));
    if (method.equals("GET")) {
      ObjectNode existing = resources.get(path);
      if (existing != null) return reply(200, existing.deepCopy());
      boolean collection =
          path.chars().filter(c -> c == '/').count() == 1 || path.endsWith("/credentials");
      if (collection) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("list");
        resources.forEach(
            (key, node) -> {
              if (key.startsWith(path + "/")
                  && !key.substring(path.length() + 1).contains("/credentials/"))
                list.add(node.deepCopy());
            });
        out.put("total", list.size());
        return reply(200, out);
      }
      return reply(404, mapper.createObjectNode());
    }
    if (method.equals("PUT")) return reply(200, put(path, (ObjectNode) body));
    if (method.equals("DELETE")) {
      resources.remove(path);
      return reply(200, mapper.createObjectNode().put("deleted", "1"));
    }
    return reply(404, mapper.createObjectNode());
  }

  private GatewayClient.Reply reply(int code, JsonNode data) {
    return new GatewayClient.Reply(code, data);
  }
}
