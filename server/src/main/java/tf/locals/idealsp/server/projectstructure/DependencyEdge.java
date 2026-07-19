package tf.locals.idealsp.server.projectstructure;

public class DependencyEdge {
  private String from;
  private String to;
  private String scope;

  public DependencyEdge() {}

  public DependencyEdge(String from, String to, String scope) {
    this.from = from;
    this.to = to;
    this.scope = scope;
  }

  public String getFrom() { return from; }
  public void setFrom(String from) { this.from = from; }

  public String getTo() { return to; }
  public void setTo(String to) { this.to = to; }

  public String getScope() { return scope; }
  public void setScope(String scope) { this.scope = scope; }
}
