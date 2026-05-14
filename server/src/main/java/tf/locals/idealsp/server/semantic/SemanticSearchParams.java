package tf.locals.idealsp.server.semantic;

import java.util.Map;

public class SemanticSearchParams {
  private String pattern;
  private String scope;
  private String language;
  private String fileUri;
  private Map<String, Map<String, String>> constraints;

  public SemanticSearchParams() {}

  public String getPattern() { return pattern; }
  public void setPattern(String pattern) { this.pattern = pattern; }

  public String getScope() { return scope; }
  public void setScope(String scope) { this.scope = scope; }

  public String getLanguage() { return language; }
  public void setLanguage(String language) { this.language = language; }

  public String getFileUri() { return fileUri; }
  public void setFileUri(String fileUri) { this.fileUri = fileUri; }

  public Map<String, Map<String, String>> getConstraints() { return constraints; }
  public void setConstraints(Map<String, Map<String, String>> constraints) { this.constraints = constraints; }
}
