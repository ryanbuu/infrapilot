package dev.ryanbuu.infrapilot.ups;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class UpsDemo {
  private final ObjectMapper mapper;
  private final Map<String, JsonNode> state = new LinkedHashMap<>();

  public UpsDemo(ObjectMapper mapper) {
    this.mapper = mapper;
    state.put(
        "/api/multi-nut/targets",
        json(
            "{\"success\":true,\"targets\":[{\"id\":1,\"name\":\"APC UPS · 演示设备\",\"ups_name\":\"ups\",\"host\":\"nut.example.com\",\"port\":3493,\"enabled\":true,\"is_primary\":true,\"nut_mode\":\"netclient\",\"polling_interval\":10,\"retention_days\":30,\"db_strategy\":\"shared\"}]}"));
    state.put(
        "/api/settings/variables",
        json(
            "{\"success\":true,\"data\":{\"currency\":\"CNY\",\"price_per_kwh\":0.6,\"co2_factor\":0.55,\"ups_realpower_nominal\":390,\"timezone\":\"Asia/Shanghai\",\"polling_interval\":10}}"));
    state.put(
        "/api/settings/operations",
        json(
            "{\"success\":true,\"data\":{\"measured_power_metric_key\":\"ups.realpower\",\"load_metric_key\":\"ups.load\",\"nominal_power_metric_key\":\"ups.realpower.nominal\",\"realpower_formula\":\"load * nominal / 100\",\"power_calibration_factor\":1}}"));
    state.put(
        "/api/options/options-from-initial-setup",
        json(
            "{\"success\":true,\"data\":{\"server_name\":\"UPS Demo\",\"timezone\":\"Asia/Shanghai\",\"monitoring_profile\":\"single\",\"ups_realpower_nominal\":390}}"));
    state.put("/api/options/script-actions", json("{\"success\":true,\"actions\":[]}"));
    state.put(
        "/auth/api/admin/users",
        json(
            "{\"users\":[{\"id\":1,\"username\":\"demo-admin\",\"role\":\"administrator\",\"is_admin\":true}]}"));
    state.put(
        "/auth/api/admin/oidc",
        json(
            "{\"success\":true,\"data\":{\"enabled\":false,\"issuer\":\"\",\"client_id\":\"\",\"client_secret\":\"demo-secret\"}}"));
    state.put(
        "/api/settings/log",
        json("{\"success\":true,\"data\":{\"log\":true,\"level\":\"INFO\",\"werkzeug\":false}}"));
  }

  private JsonNode json(String s) {
    try {
      return mapper.readTree(s);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private ObjectNode ok() {
    return mapper.createObjectNode().put("success", true);
  }

  public synchronized UpsClient.Reply call(
      String method, String url, JsonNode body, boolean binary) {
    String path = url.split("\\?", 2)[0];
    JsonNode result;
    if (binary)
      return new UpsClient.Reply(
          null,
          "Isolated UPS demo export\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
          "text/plain");
    if (!method.equals("GET")) {
      if (path.equals("/api/report/generate"))
        result =
            ok().set(
                    "data",
                    json(
                        "{\"energy_kwh\":1.8,\"cost\":1.08,\"uptime\":\"24 h\",\"power_average_w\":75,\"events\":[]}"));
      else if (path.equals("/api/multi-nut/targets")
          || path.equals("/api/options/script-actions")
          || path.equals("/auth/api/admin/users")) {
        String key =
            path.endsWith("targets") ? "targets" : path.endsWith("users") ? "users" : "actions";
        ArrayNode rows = (ArrayNode) state.get(path).get(key);
        ObjectNode row = (ObjectNode) body.deepCopy();
        row.put("id", rows.size() + 10);
        rows.add(row);
        result = ok().set("data", row);
      } else if (path.matches("/api/multi-nut/targets/[0-9]+")
          || path.matches("/api/options/script-actions/[0-9]+")) {
        String base = path.substring(0, path.lastIndexOf('/')),
            key = base.endsWith("targets") ? "targets" : "actions";
        ArrayNode rows = (ArrayNode) state.get(base).get(key);
        String id = path.substring(path.lastIndexOf('/') + 1);
        for (int i = 0; i < rows.size(); i++)
          if (rows.get(i).path("id").asText().equals(id)) {
            if (method.equals("DELETE")) rows.remove(i);
            else {
              ObjectNode row = (ObjectNode) rows.get(i);
              body.fields().forEachRemaining(e -> row.set(e.getKey(), e.getValue()));
            }
            break;
          }
        result = ok();
      } else {
        result = ok().set("data", body == null ? mapper.createObjectNode() : body.deepCopy());
        if (state.containsKey(path)) state.put(path, result.deepCopy());
        if (path.startsWith("/api/advanced/nut/config/"))
          state.put(path, ok().set("config", body.deepCopy()));
      }
      return new UpsClient.Reply(result, null, "application/json");
    }
    if (state.containsKey(path)) result = state.get(path).deepCopy();
    else if (path.matches("/api/multi-nut/targets/[0-9]+")) {
      result =
          ok().set("target", state.get("/api/multi-nut/targets").path("targets").get(0).deepCopy());
    } else if (path.equals("/api/data/all") || path.equals("/api/ups/json"))
      result =
          ok().set(
                  "data",
                  json(
                      "{\"battery_charge\":85,\"battery_runtime\":1200,\"battery_voltage\":13.2,\"battery_voltage_nominal\":12,\"ups_load\":20,\"ups_realpower\":78,\"ups_realpower_nominal\":390,\"ups_status\":\"OL CHRG\",\"ups_test_result\":\"Done and passed\",\"input_voltage\":220,\"output_voltage\":220,\"device_model\":\"Back-UPS 650\",\"device_mfr\":\"APC\"}"));
    else if (path.equals("/api/table/dynamic")
        || path.endsWith("/history") && !path.contains("upsrw")) {
      ArrayNode rows = mapper.createArrayNode();
      for (int i = 0; i < 60; i++) {
        rows.addObject()
            .put("timestamp_utc", Instant.now().minusSeconds((60 - i) * 60).toString())
            .put("ups_realpower", 70 + Math.sin(i / 4.0) * 10)
            .put("ups_load", 18 + Math.sin(i / 4.0) * 3)
            .put("battery_charge", 80 + i / 10.0)
            .put("battery_runtime", 1100 + i)
            .put("battery_voltage", 13.2)
            .put("input_voltage", 220 + Math.sin(i / 3.0))
            .put("output_voltage", 220 + Math.sin(i / 3.0));
      }
      result = ok().set("rows", rows);
    } else if (path.equals("/api/upscmd/list"))
      result =
          ok().set(
                  "commands",
                  json(
                      "[{\"command\":\"test.battery.start.quick\",\"description\":\"快速电池自检\"},{\"command\":\"test.battery.stop\",\"description\":\"停止电池自检\"},{\"command\":\"beeper.toggle\",\"description\":\"切换蜂鸣器\"}]"));
    else if (path.equals("/api/upsrw/list"))
      result =
          ok().set(
                  "variables",
                  json(
                      "[{\"name\":\"battery.charge.low\",\"value\":\"20\",\"description\":\"低电量阈值\"}]"));
    else if (path.equals("/api/table/events"))
      result =
          ok().set(
                  "rows",
                  json(
                      "[{\"id\":1,\"event_type\":\"ONLINE\",\"message\":\"市电供电恢复\",\"timestamp\":\"2026-11-10T00:00:00Z\",\"acknowledged\":false}]"));
    else if (path.equals("/api/advanced/nut/files"))
      result =
          ok().set(
                  "files",
                  json(
                      "[{\"name\":\"nut.conf\"},{\"name\":\"ups.conf\"},{\"name\":\"upsmon.conf\"}]"));
    else if (path.startsWith("/api/advanced/nut/config/"))
      result = ok().set("config", json("{\"content\":\"MODE=netclient\\n\"}"));
    else if (path.endsWith("/status"))
      result = ok().set("status", json("{\"upsd\":true,\"upsmon\":true}"));
    else if (path.contains("/users/") && path.endsWith("permissions"))
      result = ok().set("permissions", json("{\"home\":true,\"energy\":true}"));
    else if (path.contains("/users/") && path.endsWith("options-tabs"))
      result =
          ok().set(
                  "options_tabs",
                  json(
                      "{\"options\":true,\"email\":true,\"extranotifs\":false,\"webhook\":false}"));
    else if (path.equals("/api/database/stats"))
      result = ok().set("data", json("{\"size_mb\":4.2,\"tables\":12,\"records\":3600}"));
    else if (path.equals("/api/multi-nut/renamer/catalog"))
      result =
          ok().set(
                  "data",
                  json("{\"mappings\":{\"ups_realpower\":\"ups.realpower\"},\"metrics\":[]}"));
    else if (path.contains("energy"))
      result = ok().set("data", json("{\"energy_kwh\":1.8,\"cost\":1.08,\"co2_kg\":0.99}"));
    else if (path.endsWith("/metrics") || path.endsWith("/stats"))
      result = ok().set("data", json("{\"minimum\":65,\"average\":75,\"maximum\":85}"));
    else result = ok().set("data", mapper.createArrayNode());
    return new UpsClient.Reply(result, null, "application/json");
  }
}
