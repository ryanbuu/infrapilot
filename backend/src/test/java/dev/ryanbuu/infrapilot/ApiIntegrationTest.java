package dev.ryanbuu.infrapilot;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1",
      "infrapilot.viewer-password=test-viewer-password"
    })
@AutoConfigureMockMvc
class ApiIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  String payload(String name, String type, long ttl, List<String> values, String revision)
      throws Exception {
    Map<String, Object> value = new HashMap<>();
    value.put("name", name);
    value.put("type", type);
    value.put("ttl", ttl);
    value.put("values", values);
    if (revision != null) value.put("expectedRevision", revision);
    return mapper.writeValueAsString(value);
  }

  JsonNode record(String name) throws Exception {
    JsonNode records =
        mapper
            .readTree(
                mvc.perform(get("/api/zones/intra/records").with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("records");
    for (JsonNode r : records) if (r.get("name").asText().equals(name)) return r;
    return null;
  }

  @Test
  void realLoginCreatesAnAuthenticatedSession() throws Exception {
    var result =
        mvc.perform(
                post("/api/login")
                    .with(csrf())
                    .param("username", "admin")
                    .param("password", "infrapilot-demo"))
            .andExpect(status().isOk())
            .andReturn();
    var session =
        (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false);
    mvc.perform(get("/api/session").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("admin"));
  }

  @Test
  void extendedRecordsCanBeComparedAcrossBothDnsNodes() throws Exception {
    for (String type : List.of("TLSA", "SSHFP", "HTTPS", "SVCB", "DNSKEY", "DHCID"))
      mvc.perform(
              get("/api/zones/intra/probe")
                  .param("name", "www")
                  .param("type", type)
                  .with(user("admin").roles("ADMIN")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(2));
    mvc.perform(
            get("/api/zones/intra/probe")
                .param("name", "www")
                .param("type", "AXFR")
                .with(user("admin").roles("ADMIN")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void authenticationCsrfAndViewerAreEnforced() throws Exception {
    mvc.perform(get("/api/zones")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/zones/intra/records")
                .with(user("admin").roles("ADMIN"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/zones/intra/records")
                .with(user("viewer").roles("VIEWER"))
                .with(csrf())
                .contentType("application/json")
                .content(payload("viewer-blocked", "A", 300, List.of("192.0.2.1"), null)))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/zones").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
    mvc.perform(get("/api/audit").with(user("viewer").roles("VIEWER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void rrsetLifecycleConflictAndAudit() throws Exception {
    String name = "lifecycle.intra.example.com.";
    mvc.perform(
            post("/api/zones/intra/records")
                .with(user("operator").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(payload(name, "A", 300, List.of("192.0.2.1", "192.0.2.2"), null)))
        .andExpect(status().isOk());
    JsonNode before = record(name);
    assertThat(before.get("values")).hasSize(2);
    mvc.perform(
            post("/api/zones/intra/records")
                .with(user("operator").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(payload(name, "A", 300, List.of("192.0.2.9"), null)))
        .andExpect(status().isConflict());
    String rev = before.get("revision").asText();
    mvc.perform(
            put("/api/zones/intra/records")
                .with(user("operator").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(payload(name, "A", 600, List.of("192.0.2.3"), rev)))
        .andExpect(status().isOk());
    mvc.perform(
            put("/api/zones/intra/records")
                .with(user("operator").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(payload(name, "A", 300, List.of("192.0.2.4"), rev)))
        .andExpect(status().isConflict());
    assertThat(record(name).get("ttl").asInt()).isEqualTo(600);
    String deletion =
        mapper.writeValueAsString(
            Map.of(
                "name",
                name,
                "type",
                "A",
                "expectedRevision",
                record(name).get("revision").asText()));
    mvc.perform(
            post("/api/zones/intra/records/delete")
                .with(user("operator").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(deletion))
        .andExpect(status().isOk());
    assertThat(record(name)).isNull();
    JsonNode events =
        mapper.readTree(
            mvc.perform(get("/api/audit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var ours = new ArrayList<JsonNode>();
    events.forEach(
        e -> {
          if (e.get("recordName").asText().equals(name)) ours.add(e);
        });
    assertThat(ours).hasSize(3);
    assertThat(ours).allSatisfy(e -> assertThat(e.get("status").asText()).isEqualTo("SUCCESS"));
  }

  @Test
  void managedZonesDelegationAndInvalidDataAreProtected() throws Exception {
    for (String zone : List.of("dhcp", "reverse-internal", "reverse-iot"))
      mvc.perform(
              post("/api/zones/" + zone + "/records")
                  .with(user("admin").roles("ADMIN"))
                  .with(csrf())
                  .contentType("application/json")
                  .content(payload("test", "A", 300, List.of("192.0.2.1"), null)))
          .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/zones/intra/records")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    payload(
                        "injected.dhcp.intra.example.com.", "A", 300, List.of("192.0.2.1"), null)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/zones/intra/records")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(payload("bad.external.example.", "A", 300, List.of("192.0.2.1"), null)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/zones/intra/records")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(payload("invalid-ip", "A", 300, List.of("999.1.1.1"), null)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/zones/intra/records")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(payload("ttl", "A", -1, List.of("192.0.2.1"), null)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/zones/intra/records")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(payload("apphost", "CNAME", 300, List.of("nas.intra.example.com."), null)))
        .andExpect(status().isConflict());
    mvc.perform(
            get("/api/zones/intra/probe")
                .param("name", "dhcp-a")
                .param("type", "A")
                .with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].serial").isNumber())
        .andExpect(jsonPath("$[1].reachable").value(true));
  }
}
