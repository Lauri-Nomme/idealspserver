package tf.locals.idealsp.server.projectstructure;

import java.util.List;
import java.util.Map;

public class ProjectStructureResult {
  private String workspaceRoot;
  private Map<String, String> project;
  private List<ModuleInfo> modules;
  private DependencyGraph dependencyGraph;
  private List<EntryPoint> entryPoints;
  private List<SourceRootInfo> sourceLayout;
  private String message;

  public ProjectStructureResult() {}

  public String getWorkspaceRoot() { return workspaceRoot; }
  public void setWorkspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; }

  public Map<String, String> getProject() { return project; }
  public void setProject(Map<String, String> project) { this.project = project; }

  public List<ModuleInfo> getModules() { return modules; }
  public void setModules(List<ModuleInfo> modules) { this.modules = modules; }

  public DependencyGraph getDependencyGraph() { return dependencyGraph; }
  public void setDependencyGraph(DependencyGraph dependencyGraph) { this.dependencyGraph = dependencyGraph; }

  public List<EntryPoint> getEntryPoints() { return entryPoints; }
  public void setEntryPoints(List<EntryPoint> entryPoints) { this.entryPoints = entryPoints; }

  public List<SourceRootInfo> getSourceLayout() { return sourceLayout; }
  public void setSourceLayout(List<SourceRootInfo> sourceLayout) { this.sourceLayout = sourceLayout; }

  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
}
