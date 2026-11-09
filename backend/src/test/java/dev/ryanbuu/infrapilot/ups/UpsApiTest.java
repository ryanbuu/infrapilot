package dev.ryanbuu.infrapilot.ups;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:ups-api", "infrapilot.mode=demo"})
@AutoConfigureMockMvc
class UpsApiTest {
  static Path backups;

  static {
    try {
      backups = Files.createTempDirectory("ups-test-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry r) {
    r.add("infrapilot.ups.backup-directory", backups::toString);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired UpsCatalog catalog;

  String id(String method, String path) {
    return catalog.find(method, path).orElseThrow().path("id").asText();
  }

  JsonNode postJson(String path, String body, String actor) throws Exception {
    return mapper.readTree(
        mvc.perform(
                post(path)
                    .with(user(actor).roles("ADMIN"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void authorizationAndCsrf() throws Exception {
    mvc.perform(get("/api/ups/catalog")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/ups/catalog").with(user("viewer").roles("VIEWER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/ups/execute")
                .with(user("admin").roles("ADMIN"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void fixedCatalogRejectsNotificationsTraversalAndUnscopedAccess() {
    assertThat(catalog.publicCatalog().path("notifications").asBoolean()).isFalse();
    for (JsonNode op : catalog.publicCatalog().path("operations"))
      assertThat(op.path("path").asText())
          .doesNotContain("/report/send", "/settings/mail", "ntfy", "telegram", "webhook");
    assertThatThrownBy(() -> catalog.operation("http://evil.example")).hasMessageContaining("未知");
    String config = id("GET", "/api/advanced/nut/config/<filename>");
    assertThatThrownBy(
            () ->
                catalog.resolve(
                    config, mapper.readTree("{\"params\":{\"filename\":\"..%2fsecret\"}}")))
        .hasMessageContaining("无效");
    assertThatThrownBy(() -> catalog.resolve(id("GET", "/api/data/all"), mapper.createObjectNode()))
        .hasMessageContaining("选择");
  }

  @Test
  void eventAcknowledgementCannotBeCalledAsRead() throws Exception {
    mvc.perform(
            post("/api/ups/query/" + id("POST", "/api/table/events"))
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"targetId\":1,\"body\":{\"event_id\":1}}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void previewIsBoundToActorAndConsumedOnlyOnceAndBackedUp() throws Exception {
    var preview =
        postJson(
            "/api/ups/preview/" + id("POST", "/api/upscmd/execute"),
            "{\"targetId\":1,\"body\":{\"command\":\"test.battery.start.quick\"}}",
            "operator");
    String request = "{\"token\":\"" + preview.path("token").asText() + "\"}";
    mvc.perform(
            post("/api/ups/execute")
                .with(user("other").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(request))
        .andExpect(status().isConflict());
    postJson("/api/ups/execute", request, "operator");
    mvc.perform(
            post("/api/ups/execute")
                .with(user("operator").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(request))
        .andExpect(status().isConflict());
    try (var files = Files.list(backups)) {
      assertThat(files.count()).isGreaterThan(0);
    }
  }

  @Test
  void secretsAreMaskedAndRetainedOnUnchangedEdits() throws Exception {
    var read = postJson("/api/ups/query/" + id("GET", "/auth/api/admin/oidc"), "{}", "admin");
    assertThat(read.toString()).doesNotContain("demo-secret").contains(UpsService.MASK);
    var preview =
        postJson(
            "/api/ups/preview/" + id("PUT", "/auth/api/admin/oidc"),
            "{\"body\":{\"client_id\":\"new-client\",\"client_secret\":\""
                + UpsService.MASK
                + "\"}}",
            "admin");
    assertThat(preview.toString()).doesNotContain("demo-secret");
    postJson("/api/ups/execute", "{\"token\":\"" + preview.path("token").asText() + "\"}", "admin");
  }

  @Test
  void requiredTargetCannotBeOverriddenInBodyAndQuery() {
    assertThatThrownBy(
            () ->
                catalog.resolve(
                    id("POST", "/api/upsrw/set"),
                    mapper.readTree(
                        "{\"targetId\":1,\"body\":{\"target_id\":2,\"name\":\"battery.charge.low\",\"value\":\"20\"}}")))
        .hasMessageContaining("设备选择器");
  }
}
