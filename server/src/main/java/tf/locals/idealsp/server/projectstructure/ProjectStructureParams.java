package tf.locals.idealsp.server.projectstructure;

public class ProjectStructureParams {
  private String scope;

  public ProjectStructureParams() {}

  public ProjectStructureParams(String scope) {
    this.scope = scope;
  }

  public String getScope() { return scope; }
  public void setScope(String scope) { this.scope = scope; }
}
