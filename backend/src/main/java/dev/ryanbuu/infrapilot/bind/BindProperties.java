package dev.ryanbuu.infrapilot.bind;

import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "infrapilot.bind")
public class BindProperties {
  private boolean enabled;
  private String privateKeyFile = "", knownHostsFile = "";
  private String backupDirectory = "./data/bind";
  private List<Node> nodes = new ArrayList<>();

  public record Node(String id, String label, String host, int port, String user) {}

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public String getPrivateKeyFile() {
    return privateKeyFile;
  }

  public void setPrivateKeyFile(String v) {
    privateKeyFile = v;
  }

  public String getKnownHostsFile() {
    return knownHostsFile;
  }

  public void setKnownHostsFile(String v) {
    knownHostsFile = v;
  }

  public String getBackupDirectory() {
    return backupDirectory;
  }

  public void setBackupDirectory(String v) {
    backupDirectory = v;
  }

  public List<Node> getNodes() {
    return nodes;
  }

  public void setNodes(List<Node> v) {
    nodes = v;
  }
}
