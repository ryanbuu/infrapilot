package dev.ryanbuu.infrapilot.bind;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bind")
public class BindController {
  private final BindService service;

  public BindController(BindService service) {
    this.service = service;
  }

  @GetMapping
  public ObjectNode view() {
    return service.view();
  }

  @GetMapping("/backups")
  public List<ObjectNode> backups() {
    return service.backups();
  }

  @GetMapping("/nodes/{node}/telemetry")
  public ObjectNode telemetry(@PathVariable String node) {
    return service.telemetry(node);
  }

  @PostMapping("/preview")
  public ObjectNode preview(@RequestBody BindService.Change c, Authentication a) {
    return service.change(c, a.getName(), true);
  }

  @PostMapping("/changes")
  public ObjectNode change(@RequestBody BindService.Change c, Authentication a) {
    return service.change(c, a.getName(), false);
  }

  @PostMapping("/commands")
  public ObjectNode command(@RequestBody BindService.Command c, Authentication a) {
    return service.command(c, a.getName());
  }

  @PostMapping("/keys/generate")
  public ResponseEntity<ObjectNode> key(@RequestBody ObjectNode q) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.generateKey(q));
  }

  @PostMapping("/recover")
  public ObjectNode recover() {
    return service.retryRecovery();
  }
}
