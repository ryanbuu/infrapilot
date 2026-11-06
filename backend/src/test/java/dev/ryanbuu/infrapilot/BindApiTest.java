package dev.ryanbuu.infrapilot;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:bind-api",
      "infrapilot.mode=demo",
      "infrapilot.bind.backup-directory=target/bind-api-backups"
    })
@AutoConfigureMockMvc
class BindApiTest {
  @Autowired MockMvc mvc;

  @Test
  void adminAndCsrfAreRequiredForAllManagementEndpoints() throws Exception {
    for (String path : new String[] {"", "/backups", "/nodes/bind1/telemetry"}) {
      mvc.perform(get("/api/bind" + path)).andExpect(status().isUnauthorized());
      mvc.perform(get("/api/bind" + path).with(user("viewer").roles("VIEWER")))
          .andExpect(status().isForbidden());
    }
    mvc.perform(get("/api/bind").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.writable").value(true));
    for (String path :
        new String[] {"preview", "changes", "commands", "recover", "keys/generate"}) {
      mvc.perform(
              post("/api/bind/" + path)
                  .with(user("admin").roles("ADMIN"))
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isForbidden());
      mvc.perform(
              post("/api/bind/" + path)
                  .with(user("viewer").roles("VIEWER"))
                  .with(csrf())
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            post("/api/bind/keys/generate")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\"test.example.net\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
  }
}
