package dev.ryanbuu.infrapilot.forwarding;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/forwarding")
public class ForwardingController {
  private final ForwardingService service;

  public ForwardingController(ForwardingService service) {
    this.service = service;
  }

  @GetMapping
  public ObjectNode get() {
    return service.view();
  }

  @GetMapping("/backups")
  public List<ObjectNode> backups() {
    return service.backups();
  }

  @PostMapping("/preview")
  public ObjectNode preview(@RequestBody ForwardingService.Change change, Authentication a) {
    return service.change(change, a.getName(), true);
  }

  @PostMapping("/changes")
  public ObjectNode change(@RequestBody ForwardingService.Change change, Authentication a) {
    return service.change(change, a.getName(), false);
  }

  @PostMapping("/recover")
  public ObjectNode recover() {
    return service.retryRecovery();
  }
}
