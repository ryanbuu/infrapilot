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
    properties = {"spring.datasource.url=jdbc:h2:mem:forwarding-api", "infrapilot.mode=demo"})
@AutoConfigureMockMvc
class ForwardingApiTest {
  @Autowired MockMvc mvc;

  @Test
  void viewerCanInspectButOnlyAdminWithCsrfCanMutateOrReadBackups() throws Exception {
    mvc.perform(get("/api/forwarding")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/forwarding").with(user("viewer").roles("VIEWER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.consistent").value(true));
    mvc.perform(get("/api/forwarding/backups").with(user("viewer").roles("VIEWER")))
        .andExpect(status().isForbidden());
    for (String path : new String[] {"preview", "changes", "recover"}) {
      mvc.perform(
              post("/api/forwarding/" + path)
                  .with(user("admin").roles("ADMIN"))
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isForbidden());
      mvc.perform(
              post("/api/forwarding/" + path)
                  .with(user("viewer").roles("VIEWER"))
                  .with(csrf())
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isForbidden());
    }
  }
}
