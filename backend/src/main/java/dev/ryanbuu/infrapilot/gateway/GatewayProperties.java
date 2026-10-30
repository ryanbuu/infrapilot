package dev.ryanbuu.infrapilot.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "infrapilot.gateway")
public class GatewayProperties {
  private boolean enabled;
  private String url = "",
      apiKey = "",
      backupDirectory = "./data/application-gateway",
      runtimeConfigFile = "";

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

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String v) {
    apiKey = v;
  }

  public String getBackupDirectory() {
    return backupDirectory;
  }

  public void setBackupDirectory(String v) {
    backupDirectory = v;
  }

  public String getRuntimeConfigFile() {
    return runtimeConfigFile;
  }

  public void setRuntimeConfigFile(String v) {
    runtimeConfigFile = v;
  }
}
