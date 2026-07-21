package tf.locals.idealsp.server.projectstructure;

import java.util.List;

public class SourceRootInfo {
  private String module;
  private String path;
  private String type;
  private String rootFor;
  private List<String> packages;

  public SourceRootInfo() {}

  public String getModule() { return module; }
  public void setModule(String module) { this.module = module; }

  public String getPath() { return path; }
  public void setPath(String path) { this.path = path; }

  public String getType() { return type; }
  public void setType(String type) { this.type = type; }

  public String getRootFor() { return rootFor; }
  public void setRootFor(String rootFor) { this.rootFor = rootFor; }

  public List<String> getPackages() { return packages; }
  public void setPackages(List<String> packages) { this.packages = packages; }
}
