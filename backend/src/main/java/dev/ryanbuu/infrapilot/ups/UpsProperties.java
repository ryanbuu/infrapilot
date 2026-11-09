package dev.ryanbuu.infrapilot.ups;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "infrapilot.ups")
public class UpsProperties {
  private boolean enabled;
  private String url = "", username = "", password = "", backupDirectory = "./data/ups";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String v) {
    url = v;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String v) {
    username = v;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String v) {
    password = v;
  }

  public String getBackupDirectory() {
    return backupDirectory;
  }

  public void setBackupDirectory(String v) {
    backupDirectory = v;
  }
}
