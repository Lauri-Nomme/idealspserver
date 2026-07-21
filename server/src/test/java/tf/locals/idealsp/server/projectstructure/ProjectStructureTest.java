package tf.locals.idealsp.server.projectstructure;

import org.junit.Assert;
import org.junit.Test;
import tf.locals.idealsp.server.lsp.LspServerTestBase;
import tf.locals.idealsp.server.TestUtil;

public class ProjectStructureTest extends LspServerTestBase {
  @Override
  protected String getProjectRelativePath() {
    return "lsp/project1";
  }

  @Test
  public void testProjectStructureDefaultScope() {
    var result = TestUtil.getNonBlockingEdt(
        server().projectStructure(new ProjectStructureParams(null)), 30000);

    Assert.assertNotNull("result must not be null", result);

    Assert.assertNotNull("workspaceRoot must not be null", result.getWorkspaceRoot());
    Assert.assertFalse("workspaceRoot must not be empty", result.getWorkspaceRoot().isEmpty());

    var projectInfo = result.getProject();
    Assert.assertNotNull("project info must not be null", projectInfo);
    Assert.assertNotNull("project name must not be null", projectInfo.get("name"));
    Assert.assertNotNull("project basePath must not be null", projectInfo.get("basePath"));

    var modules = result.getModules();
    Assert.assertNotNull("modules must not be null", modules);
    Assert.assertFalse("modules must not be empty", modules.isEmpty());

    var firstModule = modules.get(0);
    Assert.assertNotNull("module name must not be null", firstModule.getName());
    Assert.assertNotNull("module type must not be null", firstModule.getType());
    Assert.assertNotNull("module contentRoots must not be null", firstModule.getContentRoots());
    Assert.assertFalse("module contentRoots must not be empty", firstModule.getContentRoots().isEmpty());
    Assert.assertNotNull("module facets must not be null", firstModule.getFacets());
    Assert.assertNotNull("module libraryDependencies must not be null", firstModule.getLibraryDependencies());
  }

  @Test
  public void testProjectStructureDependencyGraph() {
    var result = TestUtil.getNonBlockingEdt(
        server().projectStructure(new ProjectStructureParams(null)), 30000);

    var graph = result.getDependencyGraph();
    Assert.assertNotNull("dependencyGraph must not be null", graph);
    Assert.assertNotNull("dependencyGraph.edges must not be null", graph.getEdges());
  }

  @Test
  public void testProjectStructureSourceLayout() {
    var result = TestUtil.getNonBlockingEdt(
        server().projectStructure(new ProjectStructureParams(null)), 30000);

    var layout = result.getSourceLayout();
    Assert.assertNotNull("sourceLayout must not be null", layout);
    Assert.assertFalse("sourceLayout must not be empty", layout.isEmpty());

    var firstLayout = layout.get(0);
    Assert.assertNotNull("layout module must not be null", firstLayout.getModule());
    Assert.assertNotNull("layout path must not be null", firstLayout.getPath());
    Assert.assertNotNull("layout type must not be null", firstLayout.getType());
    Assert.assertNotNull("layout packages must not be null", firstLayout.getPackages());
  }

  @Test
  public void testProjectStructureScopeFilterModules() {
    var result = TestUtil.getNonBlockingEdt(
        server().projectStructure(new ProjectStructureParams("modules")), 30000);

    Assert.assertNotNull("modules scope result must not be null", result);
    Assert.assertFalse("modules must be present", result.getModules().isEmpty());
    Assert.assertTrue("sourceLayout should be empty for modules scope",
        result.getSourceLayout() == null || result.getSourceLayout().isEmpty());
    Assert.assertTrue("entryPoints should be empty for modules scope",
        result.getEntryPoints() == null || result.getEntryPoints().isEmpty());
  }

  @Test
  public void testProjectStructureScopeFilterEntry() {
    var result = TestUtil.getNonBlockingEdt(
        server().projectStructure(new ProjectStructureParams("entry")), 30000);

    Assert.assertNotNull("entry scope result must not be null", result);
    Assert.assertTrue("modules should be empty for entry scope",
        result.getModules() == null || result.getModules().isEmpty());
    Assert.assertNotNull("entryPoints must not be null for entry scope", result.getEntryPoints());
  }

  @Test
  public void testProjectStructureScopeFilterSource() {
    var result = TestUtil.getNonBlockingEdt(
        server().projectStructure(new ProjectStructureParams("source")), 30000);

    Assert.assertNotNull("source scope result must not be null", result);
    Assert.assertTrue("dependencyGraph should have no edges for source scope",
        result.getDependencyGraph() == null
            || result.getDependencyGraph().getEdges() == null
            || result.getDependencyGraph().getEdges().isEmpty());
    Assert.assertFalse("sourceLayout must not be empty for source scope",
        result.getSourceLayout() == null || result.getSourceLayout().isEmpty());
  }

  @Test
  public void testProjectStructureScopeFilterAll() {
    var result = TestUtil.getNonBlockingEdt(
        server().projectStructure(new ProjectStructureParams("all")), 30000);

    Assert.assertNotNull("all scope result must not be null", result);
    Assert.assertFalse("modules must be present", result.getModules().isEmpty());
    Assert.assertNotNull("dependencyGraph must be present", result.getDependencyGraph());
    Assert.assertFalse("sourceLayout must be present",
        result.getSourceLayout() == null || result.getSourceLayout().isEmpty());
    Assert.assertNotNull("entryPoints must be present", result.getEntryPoints());
  }
}
