package dev.ryanbuu.infrapilot.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import dev.ryanbuu.infrapilot.api.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;

/** The sentinel is resolved against the current server value, never written to APISIX. */
public final class GatewaySecrets {
  public static final String MASK = "__INFRAPILOT_REDACTED__";

  private GatewaySecrets() {}

  private static boolean sensitive(String field, String path) {
    String f = field.toLowerCase(Locale.ROOT).replaceAll("[-_]", "");
    return f.matches(
            ".*(password|passwd|passphrase|secret|token|privatekey|apikey|accesskey|clientkey|certkey|encryptionkey|signingkey|authorization|cookie|keyring|encryptsalt).*")
        || (Set.of("key", "keys").contains(f)
            && (path.isEmpty()
                || path.contains("admin_key")
                || path.contains("/tls")
                || path.contains("/etcd")
                || (path.contains("/plugins/")
                    && !path.matches(
                        ".*/plugins/(limit-count|limit-conn|limit-req|proxy-cache)(/.*)?$"))));
  }

  public static JsonNode redact(JsonNode node) {
    return redact(node, "");
  }

  private static JsonNode redact(JsonNode node, String path) {
    if (node == null) return NullNode.instance;
    if (node.isObject()) {
      ObjectNode result = JsonNodeFactory.instance.objectNode();
      node.fields()
          .forEachRemaining(
              e ->
                  result.set(
                      e.getKey(),
                      sensitive(e.getKey(), path) && !e.getValue().isNull()
                          ? TextNode.valueOf(MASK)
                          : redact(e.getValue(), path + "/" + e.getKey())));
      return result;
    }
    if (node.isArray()) {
      ArrayNode result = JsonNodeFactory.instance.arrayNode();
      node.forEach(v -> result.add(redact(v, path)));
      return result;
    }
    return node.deepCopy();
  }

  public static JsonNode resolve(JsonNode proposed, JsonNode original) {
    if (proposed.isTextual() && proposed.asText().equals(MASK)) {
      if (original == null || original.isMissingNode() || original.isNull())
        throw new ApiException(HttpStatus.BAD_REQUEST, "新资源中的敏感字段需要填写新值");
      return original.deepCopy();
    }
    if (proposed.isObject()) {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      proposed
          .fields()
          .forEachRemaining(
              e ->
                  out.set(
                      e.getKey(),
                      resolve(e.getValue(), original == null ? null : original.path(e.getKey()))));
      return out;
    }
    if (proposed.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      for (int i = 0; i < proposed.size(); i++)
        out.add(resolve(proposed.get(i), original == null ? null : original.path(i)));
      return out;
    }
    return proposed.deepCopy();
  }

  public static String safeError(String message, JsonNode value, String apiKey) {
    String safe = message == null ? "配置未通过校验" : message;
    List<String> hidden = new ArrayList<>();
    collect(value, redact(value), hidden);
    if (apiKey != null && !apiKey.isBlank()) hidden.add(apiKey);
    for (String s : hidden) if (!s.isEmpty()) safe = safe.replace(s, "[已隐藏]");
    return safe.substring(0, Math.min(400, safe.length()));
  }

  private static void collect(JsonNode raw, JsonNode masked, List<String> out) {
    if (raw == null) return;
    if (masked.isTextual() && masked.asText().equals(MASK)) {
      if (raw.isValueNode()) out.add(raw.asText());
      else raw.forEach(v -> collect(v, TextNode.valueOf(MASK), out));
    } else if (raw.isObject())
      raw.fields().forEachRemaining(e -> collect(e.getValue(), masked.path(e.getKey()), out));
    else if (raw.isArray())
      for (int i = 0; i < raw.size(); i++) collect(raw.get(i), masked.path(i), out);
  }
}
