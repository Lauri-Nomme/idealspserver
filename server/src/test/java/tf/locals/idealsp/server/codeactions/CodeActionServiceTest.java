package tf.locals.idealsp.server.codeactions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiManager;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.TestUtil;
import tf.locals.idealsp.server.diagnostics.DiagnosticsTestBase;

import java.util.List;

@RunWith(JUnit4.class)
public class CodeActionServiceTest extends DiagnosticsTestBase {

  // --- Existing tests ---

  @Test
  public void testGetCodeActions() {
    final var text = """
        class A {
          public static void main() {
            int a = "";
            System.out.println();
          }
        }
        """;

    final var file = myFixture.configureByText("test.java", text);
    final var orExpressionRange = TestUtil.newRange(2, 8, 2, 8);

    var path = LspPath.fromVirtualFile(file.getVirtualFile());
    final var codeActionService = getProject().getService(CodeActionService.class);

    var codeActionsBeforeDiagnostic = codeActionService.collectCodeActions(path, orExpressionRange);

    Assert.assertTrue("Expected all refactoring kind", codeActionsBeforeDiagnostic.stream().allMatch(it -> it.getKind().equals(CodeActionKind.Refactor)));
    var actionTitles = codeActionsBeforeDiagnostic.stream().map(CodeAction::getTitle).sorted().toList();
    Assert.assertTrue("Expected some refactoring actions but got: " + actionTitles, !actionTitles.isEmpty());

    runAndGetDiagnostics(file);

    var allActions = codeActionService.collectCodeActions(path, orExpressionRange);
    allActions.removeAll(codeActionsBeforeDiagnostic);

    var quickFixTitles = allActions.stream().map(CodeAction::getTitle).sorted().toList();
    Assert.assertTrue("Expected some quick fix actions but got: " + quickFixTitles, !quickFixTitles.isEmpty());
    Assert.assertTrue(allActions.stream().allMatch(it -> it.getKind().equals(CodeActionKind.QuickFix)));
  }

  @Test
  public void testQuickFixFoundAndApplied() {
    final var before = """
        class A {
           final int x = "a";
        }
        """;

    final var after = """
        class A {
           final java.lang.String x = "a";
        }
        """;

    final var actionTitle = "Change field 'x' type to 'String'";

    final var file = myFixture.configureByText("test.java", before);
    final var xVariableRange = TestUtil.newRange(1, 13, 1, 13);
    var path = LspPath.fromVirtualFile(file.getVirtualFile());
    final var codeActionService = getProject().getService(CodeActionService.class);

    runAndGetDiagnostics(file);

    var codeActions = codeActionService.collectCodeActions(path, xVariableRange);

    var action = codeActions.stream()
        .filter(it -> it.getTitle().equals(actionTitle))
        .findFirst()
        .orElseThrow(() -> new AssertionError("action not found: " + actionTitle + " from: " +
            codeActions.stream().map(CodeAction::getTitle).toList()));

    Gson gson = new GsonBuilder().create();
    action.setData(gson.fromJson(gson.toJson(action.getData()), JsonObject.class));

    final var edit = codeActionService.applyCodeAction(action);

    Assert.assertEquals(after, TestUtil.applyEdits(file.getText(), edit.getChanges().get(path.toLspUri())));

    // checking the quick fix doesn't actually change the file
    final var reloaded = PsiManager.getInstance(getProject()).findFile(file.getVirtualFile());
    Assert.assertNotNull(reloaded);
    Assert.assertEquals(before, reloaded.getText());
    final var reloadedDoc = PsiDocumentManager.getInstance(getProject()).getDocument(reloaded);
    Assert.assertNotNull(reloadedDoc);
    Assert.assertEquals(before, reloadedDoc.getText());
  }

  // --- Refactoring tests ---

  // --- Refactoring availability tests ---

  @Test
  public void testExtractMethodAppearsInCodeActions() {
    assertRefactoringActionAppears("""
        class A {
          public static void main() {
            System.out.println("hello");
            System.out.println("world");
          }
        }
        """, TestUtil.newRange(2, 4, 2, 4), CodeActionService.EXTRACT_METHOD_TITLE);
  }

  @Test
  public void testIntroduceVariableAppearsInCodeActions() {
    assertRefactoringActionAppears("""
        class A {
          public static void main() {
            System.out.println(42 + 1);
          }
        }
        """, TestUtil.newRange(2, 26, 2, 26), CodeActionService.INTRODUCE_VARIABLE_TITLE);
  }

  @Test
  public void testInlineAppearsInCodeActions() {
    assertRefactoringActionAppears("""
        class A {
          static final int VALUE = 42;
          public static void main() {
            System.out.println(VALUE);
          }
        }
        """, TestUtil.newRange(3, 24, 3, 24), CodeActionService.INLINE_TITLE);
  }

  private void assertRefactoringActionAppears(String text, org.eclipse.lsp4j.Range range, String expectedTitle) {
    var file = myFixture.configureByText("test.java", text);
    var path = LspPath.fromVirtualFile(file.getVirtualFile());
    var cs = getProject().getService(CodeActionService.class);
    runAndGetDiagnostics(file);
    var actions = cs.collectCodeActions(path, range);
    var titles = actions.stream().map(CodeAction::getTitle).sorted().toList();
    Assert.assertTrue("Expected '" + expectedTitle + "' in code actions: " + titles,
        titles.stream().anyMatch(t -> expectedTitle.equals(t)));
  }

}
