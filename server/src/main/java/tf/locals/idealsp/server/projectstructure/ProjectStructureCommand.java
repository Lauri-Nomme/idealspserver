package tf.locals.idealsp.server.projectstructure;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import org.jetbrains.annotations.NotNull;
import tf.locals.idealsp.server.commands.ExecutorContext;
import tf.locals.idealsp.server.commands.LspCommand;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ProjectStructureCommand extends LspCommand<ProjectStructureResult> {
  private static final Logger LOG = Logger.getInstance(ProjectStructureCommand.class);

  private final String scope;

  public ProjectStructureCommand(String scope) {
    this.scope = scope;
  }

  @Override
  protected @NotNull Supplier<@NotNull String> getMessageSupplier() {
    return () -> "projectStructure (scope=" + scope + ")";
  }

  @Override
  protected boolean isCancellable() {
    return false;
  }

  @Override
  protected ProjectStructureResult execute(@NotNull ExecutorContext ctx) {
    Project project = ctx.getProject();
    String effectiveScope = scope != null ? scope : "all";

    ProjectStructureResult result = new ProjectStructureResult();

    String basePath = project.getBasePath();
    result.setWorkspaceRoot(basePath != null ? basePath : "");

    Map<String, String> projectInfo = new HashMap<>();
    projectInfo.put("name", project.getName());
    projectInfo.put("basePath", basePath != null ? basePath : "");
    var projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
    if (projectSdk != null) {
      projectInfo.put("sdk", projectSdk.getName());
    }
    result.setProject(projectInfo);

    result.setModules(ModuleCollector.collect(project, effectiveScope));
    result.setDependencyGraph(ModuleCollector.buildGraph(project, effectiveScope));
    result.setSourceLayout(PackageLister.list(project, effectiveScope));
    result.setEntryPoints(EntryPointFinder.find(project, effectiveScope));

    return result;
  }
}
