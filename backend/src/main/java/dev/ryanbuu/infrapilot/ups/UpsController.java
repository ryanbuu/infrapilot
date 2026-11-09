package dev.ryanbuu.infrapilot.ups;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ups")
public class UpsController {
  private final UpsService service;

  public UpsController(UpsService service) {
    this.service = service;
  }

  @GetMapping("/catalog")
  public JsonNode catalog() {
    return service.catalog();
  }

  @PostMapping("/query/{id}")
  public Object query(@PathVariable String id, @RequestBody JsonNode input, Authentication a) {
    return service.query(id, input, a.getName());
  }

  @PostMapping("/preview/{id}")
  public Object preview(@PathVariable String id, @RequestBody JsonNode input, Authentication a) {
    return service.preview(id, input, a.getName());
  }

  @PostMapping("/execute")
  public Object execute(@RequestBody Map<String, String> body, Authentication a) {
    return service.execute(body.getOrDefault("token", ""), a.getName());
  }
}
