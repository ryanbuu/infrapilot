package dev.ryanbuu.infrapilot.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/gateway")
public class GatewayController {
  private final GatewayService gateway;

  public GatewayController(GatewayService gateway) {
    this.gateway = gateway;
  }

  @GetMapping
  public Map<String, Object> overview() {
    return gateway.overview();
  }

  @GetMapping("/resources/{kind}")
  public GatewayService.Listing resources(
      @PathVariable String kind, @RequestParam(required = false) String parent) {
    return gateway.resources(kind, parent);
  }

  @GetMapping("/plugins")
  public JsonNode plugins() {
    return gateway.plugins();
  }

  @GetMapping("/schema/{kind}")
  public JsonNode schema(@PathVariable String kind, @RequestParam(required = false) String plugin) {
    return gateway.schema(kind, plugin);
  }

  @GetMapping("/runtime")
  public JsonNode runtime() {
    return gateway.runtime();
  }

  @PostMapping("/resources/{kind}/preview")
  public Map<String, Object> preview(
      @PathVariable String kind, @RequestBody GatewayService.Mutation mutation) {
    return gateway.preview(kind, mutation);
  }

  @PostMapping("/resources/{kind}/changes")
  public Map<String, Object> change(
      @PathVariable String kind, @RequestBody GatewayService.Mutation mutation, Authentication a) {
    return gateway.change(kind, mutation, a.getName());
  }

  @GetMapping("/backups")
  public List<JsonNode> backups() {
    return gateway.backups();
  }

  public record Restore(String revision) {}

  @PostMapping("/backups/{id}/preview")
  public Map<String, Object> previewRestore(@PathVariable String id) {
    return gateway.previewRestore(id);
  }

  @PostMapping("/backups/{id}/restore")
  public Map<String, Object> restore(
      @PathVariable String id, @RequestBody Restore restore, Authentication a) {
    return gateway.restore(id, restore.revision, a.getName());
  }

  public record Export(boolean includeSecrets) {}

  @PostMapping("/export")
  public JsonNode export(@RequestBody Export request, Authentication a) {
    return gateway.export(request.includeSecrets, a.getName());
  }
}
