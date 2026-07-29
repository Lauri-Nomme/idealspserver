package tf.locals.idealsp.server.lsp;

import org.eclipse.lsp4j.*;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.TestUtil;
import tf.locals.idealsp.server.refactoring.RefactorParams;
import tf.locals.idealsp.server.refactoring.RefactorResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class RefactoringCommandTest extends LspServerTestBase {
  private Path tempDataRoot;

  @Override
  protected String getProjectRelativePath() {
    return "refactoring/java/project1";
  }

  @NotNull
  @Override
  protected Path getTestDataRoot() {
    if (tempDataRoot != null) return tempDataRoot;
    return super.getTestDataRoot();
  }

  @Before
  @Override
  public void setupServer() {
    try {
      tempDataRoot = Files.createTempDirectory("refactoring-test-");
      var sourceRoot = super.getTestDataRoot().resolve(getProjectRelativePath());
      var destRoot = tempDataRoot.resolve(getProjectRelativePath());
      try (var stream = Files.walk(sourceRoot)) {
        for (var src : stream.toList()) {
          var dest = destRoot.resolve(sourceRoot.relativize(src));
          if (Files.isDirectory(src)) {
            Files.createDirectories(dest);
          } else {
            Files.copy(src, dest);
          }
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    super.setupServer();
  }

  @After
  @Override
  public void stopServer() {
    super.stopServer();
    if (tempDataRoot != null) {
      try (var stream = Files.walk(tempDataRoot)) {
        stream.sorted(Comparator.reverseOrder()).forEach(p -> {
          try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        });
      } catch (IOException ignored) {}
    }
  }

  @Test
  public void testMoveRefactoring() throws Exception {
    var filePath = LspPath.fromLocalPath(getProjectPath().resolve("src/MoveTest.java"));
    var targetPkgPath = getProjectPath().resolve("src/movepkg");

    // Open the file
    var didOpenParams = new DidOpenTextDocumentParams();
    didOpenParams.setTextDocument(new TextDocumentItem(filePath.toLspUri(), "java", 1,
        Files.readString(filePath.toPath())));
    server().getTextDocumentService().didOpen(didOpenParams);
    client().waitAndGetDiagnosticsPublished();

    // Move the class — position is at class name "MoveTest" (line 1, char 18)
    var params = new RefactorParams();
    params.setUri(filePath.toLspUri());
    params.setType("move");
    params.setPosition(new Position(2, 8));
    params.setTargetPackageUri(targetPkgPath.toUri().toString());

    var future = server().refactor(params);
    var result = TestUtil.getNonBlockingEdt(future, 30000);
    assertNotNull("Move result should not be null", result);
    assertTrue("Move should be applied", result.isApplied());
  }

  @Test
  public void testSafeDeleteRefactoring() throws Exception {
    var filePath = LspPath.fromLocalPath(getProjectPath().resolve("src/SafeDeleteTest.java"));

    // Open the file
    var didOpenParams = new DidOpenTextDocumentParams();
    didOpenParams.setTextDocument(new TextDocumentItem(filePath.toLspUri(), "java", 1,
        Files.readString(filePath.toPath())));
    server().getTextDocumentService().didOpen(didOpenParams);
    client().waitAndGetDiagnosticsPublished();

    // Safe delete unusedMethod — position is at method name (line 10, char 18)
    var params = new RefactorParams();
    params.setUri(filePath.toLspUri());
    params.setType("safe-delete");
    params.setPosition(new Position(8, 18));

    var future = server().refactor(params);
    var result = TestUtil.getNonBlockingEdt(future, 30000);
    assertNotNull("Safe-delete result should not be null", result);
    assertTrue("Safe-delete should be applied", result.isApplied());
  }

  @Test
  public void testMoveRefactoringMissingTarget() throws Exception {
    var filePath = LspPath.fromLocalPath(getProjectPath().resolve("src/MoveTest.java"));

    // Open the file so the VFS can find it
    var didOpenParams = new DidOpenTextDocumentParams();
    didOpenParams.setTextDocument(new TextDocumentItem(filePath.toLspUri(), "java", 1,
        Files.readString(filePath.toPath())));
    server().getTextDocumentService().didOpen(didOpenParams);
    client().waitAndGetDiagnosticsPublished();

    var params = new RefactorParams();
    params.setUri(filePath.toLspUri());
    params.setType("move");
    params.setPosition(new Position(2, 8));
    params.setTargetPackageUri(null);

    var future = server().refactor(params);
    var result = TestUtil.getNonBlockingEdt(future, 30000);
    assertNotNull("Move result should not be null", result);
    assertFalse("Move without target should fail", result.isApplied());
  }
}
