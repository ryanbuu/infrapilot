package dev.ryanbuu.infrapilot;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.ryanbuu.infrapilot.gateway.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {"spring.datasource.url=jdbc:h2:mem:gateway-api", "infrapilot.mode=demo"})
@AutoConfigureMockMvc
class GatewayApiTest {
  static final Path BACKUPS;

  static {
    try {
      BACKUPS = Files.createTempDirectory("infrapilot-gateway-tests-");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("infrapilot.gateway.backup-directory", BACKUPS::toString);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired GatewayClient client;

  JsonNode request(MockHttpServletRequestBuilder request) throws Exception {
    return mapper.readTree(
        mvc.perform(request.with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  JsonNode read(String kind, String id) throws Exception {
    JsonNode items = request(get("/api/gateway/resources/" + kind)).path("items");
    for (JsonNode item : items) if (item.path("id").asText().equals(id)) return item;
    throw new AssertionError("Missing resource " + id);
  }

  ObjectNode mutation(String action, String id, String revision, JsonNode value) {
    ObjectNode m =
        mapper.createObjectNode().put("action", action).put("id", id).put("revision", revision);
    if (value != null) m.set("value", value);
    return m;
  }

  JsonNode change(String kind, ObjectNode value) throws Exception {
    return request(
        post("/api/gateway/resources/" + kind + "/changes")
            .contentType("application/json")
            .content(value.toString()));
  }

  ObjectNode upstream(String name) {
    ObjectNode v = mapper.createObjectNode().put("name", name).put("type", "roundrobin");
    v.putObject("nodes").put("192.0.2.1:8080", 1);
    return v;
  }

  @Test
  void authenticationAdminAndCsrfProtectAllGatewayEndpoints() throws Exception {
    mvc.perform(get("/api/gateway")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/gateway/resources/ssls").with(user("viewer").roles("VIEWER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/gateway/export")
                .with(user("viewer").roles("VIEWER"))
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/gateway/resources/routes/changes")
                .with(user("admin").roles("ADMIN"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    assertThat(request(get("/api/gateway")).path("resources").size()).isEqualTo(12);
    assertThat(request(get("/api/gateway/plugins")).size()).isGreaterThan(5);
  }

  @Test
  void unknownFieldsSurviveEditsAndStaleRevisionsCannotOverwrite() throws Exception {
    String id = "lifecycle-" + UUID.randomUUID();
    ObjectNode value = upstream("original");
    value.putObject("labels").put("environment", "test");
    value.putObject("timeout").put("read", 17);
    change("upstreams", mutation("CREATE", id, "absent", value));
    JsonNode first = read("upstreams", id);
    ObjectNode next = ((ObjectNode) first.path("value")).deepCopy().put("name", "updated");
    ObjectNode update = mutation("UPDATE", id, first.path("revision").asText(), next);
    request(
        post("/api/gateway/resources/upstreams/preview")
            .contentType("application/json")
            .content(update.toString()));
    change("upstreams", update);
    assertThat(read("upstreams", id).at("/value/timeout/read").asInt()).isEqualTo(17);
    assertThat(read("upstreams", id).at("/value/labels/environment").asText()).isEqualTo("test");
    mvc.perform(
            post("/api/gateway/resources/upstreams/changes")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(update.toString()))
        .andExpect(status().isConflict());
  }

  @Test
  void sensitiveFieldsArePreservedAndAuditsContainNoSecrets() throws Exception {
    String id = "auth-" + UUID.randomUUID(), secret = "unique-credential-" + UUID.randomUUID();
    ObjectNode value = mapper.createObjectNode().put("desc", "before");
    value.putObject("plugins").putObject("key-auth").put("key", secret);
    change("consumers", mutation("CREATE", id, "absent", value));
    JsonNode current = read("consumers", id);
    assertThat(current.at("/value/plugins/key-auth/key").asText()).isEqualTo(GatewaySecrets.MASK);
    ObjectNode next = ((ObjectNode) current.path("value")).deepCopy().put("desc", "after");
    change("consumers", mutation("UPDATE", id, current.path("revision").asText(), next));
    assertThat(
            client
                .request("GET", "/consumers/" + id, null)
                .data()
                .at("/value/plugins/key-auth/key")
                .asText())
        .isEqualTo(secret);
    assertThat(request(get("/api/audit?limit=100")).toString()).doesNotContain(secret);
    assertThat(request(get("/api/gateway/backups")).toString()).doesNotContain(secret);
  }

  @Test
  void backupRestoreUsesFreshRevisionAndRetainsTheStateBeingReplaced() throws Exception {
    String id = "restore-" + UUID.randomUUID();
    change("upstreams", mutation("CREATE", id, "absent", upstream("first")));
    JsonNode current = read("upstreams", id);
    JsonNode result =
        change(
            "upstreams",
            mutation("UPDATE", id, current.path("revision").asText(), upstream("second")));
    String backup = result.path("backupId").asText();
    JsonNode preview = request(post("/api/gateway/backups/" + backup + "/preview"));
    current = read("upstreams", id);
    change(
        "upstreams", mutation("UPDATE", id, current.path("revision").asText(), upstream("third")));
    mvc.perform(
            post("/api/gateway/backups/" + backup + "/restore")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"revision\":\"" + preview.path("revision").asText() + "\"}"))
        .andExpect(status().isConflict());
    preview = request(post("/api/gateway/backups/" + backup + "/preview"));
    result =
        request(
            post("/api/gateway/backups/" + backup + "/restore")
                .contentType("application/json")
                .content("{\"revision\":\"" + preview.path("revision").asText() + "\"}"));
    assertThat(read("upstreams", id).at("/value/name").asText()).isEqualTo("first");
    JsonNode restoredBackup =
        mapper.readTree(
            Files.readString(BACKUPS.resolve(result.path("backupId").asText() + ".json")));
    assertThat(restoredBackup.at("/before/name").asText()).isEqualTo("third");
    assertThat(Files.getPosixFilePermissions(BACKUPS.resolve(backup + ".json")))
        .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
  }

  @Test
  void validatesBeforeWritingAndRejectsPathsOutsideTheResourceNamespace() throws Exception {
    ObjectNode body =
        mutation("CREATE", "bad-route", "absent", mapper.createObjectNode().put("uri", 1980));
    mvc.perform(
            post("/api/gateway/resources/routes/preview")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(body.toString()))
        .andExpect(status().isBadRequest());
    body = mutation("CREATE", "../../routes/infrapilot", "absent", upstream("bad"));
    mvc.perform(
            post("/api/gateway/resources/upstreams/changes")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(body.toString()))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/gateway/resources/config").with(user("admin").roles("ADMIN")))
        .andExpect(status().isNotFound());
  }

  @Test
  void credentialsUseTheirConsumerNamespaceAndPreventImplicitCascadeDeletion() throws Exception {
    String parent = "parent-" + UUID.randomUUID();
    change(
        "consumers",
        mutation("CREATE", parent, "absent", mapper.createObjectNode().put("desc", "consumer")));
    ObjectNode value = mapper.createObjectNode();
    value.putObject("plugins").putObject("key-auth").put("key", "child-secret");
    change("credentials", mutation("CREATE", "primary", "absent", value).put("parent", parent));
    JsonNode children = request(get("/api/gateway/resources/credentials").param("parent", parent));
    assertThat(children.at("/items/0/id").asText()).isEqualTo("primary");
    assertThat(children.at("/items/0/value/plugins/key-auth/key").asText())
        .isEqualTo(GatewaySecrets.MASK);
    JsonNode current = read("consumers", parent);
    mvc.perform(
            post("/api/gateway/resources/consumers/changes")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    mutation("DELETE", parent, current.path("revision").asText(), null).toString()))
        .andExpect(status().isConflict());
  }
}
