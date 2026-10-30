package dev.ryanbuu.infrapilot.gateway;

import dev.ryanbuu.infrapilot.api.ApiException;
import java.util.Arrays;
import org.springframework.http.HttpStatus;

public enum GatewayResource {
  ROUTES("routes", "路由", "route"),
  SERVICES("services", "服务", "service"),
  UPSTREAMS("upstreams", "上游", "upstream"),
  CONSUMERS("consumers", "消费者", "consumer"),
  CREDENTIALS("credentials", "消费者凭据", "credential"),
  CONSUMER_GROUPS("consumer_groups", "消费者组", "consumer_group"),
  SSLS("ssls", "证书", "ssl"),
  GLOBAL_RULES("global_rules", "全局规则", "global_rule"),
  PLUGIN_CONFIGS("plugin_configs", "插件配置", "plugin_config"),
  PLUGIN_METADATA("plugin_metadata", "插件元数据", "plugin_metadata"),
  STREAM_ROUTES("stream_routes", "TCP / UDP 路由", "stream_route"),
  PROTOS("protos", "Protobuf", "proto"),
  SECRETS("secrets", "密钥管理", "secret");
  public final String id, label, schema;

  GatewayResource(String id, String label, String schema) {
    this.id = id;
    this.label = label;
    this.schema = schema;
  }

  public static GatewayResource of(String id) {
    return Arrays.stream(values())
        .filter(r -> r.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "网关资源类型不存在"));
  }

  public String path(String parent, String resourceId) {
    String prefix =
        this == CREDENTIALS ? "/consumers/" + segment(parent) + "/credentials" : "/" + id;
    if (resourceId == null) return prefix;
    if (this == SECRETS) {
      String[] parts = resourceId.split("/", -1);
      if (parts.length != 2)
        throw new ApiException(HttpStatus.BAD_REQUEST, "密钥管理 ID 应为 provider/id");
      return prefix + "/" + segment(parts[0]) + "/" + segment(parts[1]);
    }
    return prefix + "/" + segment(resourceId);
  }

  public static String segment(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}") || id.contains(".."))
      throw new ApiException(HttpStatus.BAD_REQUEST, "资源 ID 仅支持字母、数字、点、下划线和短横线，最长 100 字符");
    return id;
  }
}
