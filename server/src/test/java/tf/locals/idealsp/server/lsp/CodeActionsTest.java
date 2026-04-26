package tf.locals.idealsp.server.lsp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.intellij.psi.PsiManager;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Assert;
import org.junit.Test;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.TestUtil;
import tf.locals.idealsp.server.util.MiscUtil;

import java.nio.file.Files;
import java.util.Objects;
import java.util.stream.Stream;

public class CodeActionsTest extends LspServerTestBase {
  @Override
  protected String getProjectRelativePath() {
    return "lsp/project1";
  }

  @Test
  public void testFindAndResolveCodeAction() {
    var invokedActionName = "Change variable 'a' type to 'String'";

    final var filePath = LspPath.fromLocalPath(getProjectPath().resolve("src/CodeActions.java"));

    final var didOpenTextDocumentParams = MiscUtil.with(new DidOpenTextDocumentParams(),
        params -> params.setTextDocument(MiscUtil.with(new TextDocumentItem(), item -> {
          item.setUri(filePath.toLspUri());

          item.setText(MiscUtil.makeThrowsUnchecked(() -> Files.readString(filePath.toPath())));
          item.setVersion(1);
        })));

    server().getTextDocumentService().didOpen(didOpenTextDocumentParams);

    client().waitAndGetDiagnosticsPublished();

    final var params = new CodeActionParams();
    params.setTextDocument(new TextDocumentIdentifier(filePath.toLspUri()));
    params.setRange(TestUtil.newRange(3, 12, 3, 12));
    params.setContext(new CodeActionContext());

    final var codeActions =
        TestUtil.getNonBlockingEdt(server().getTextDocumentService().codeAction(params), 30000);

    // Verify exact action list for IntelliJ 2026.1
    var expectedActions = Stream.of(
        "Change variable 'a' type to 'String'",
        "Copy string literal text to the clipboard",
        "Inject language or reference",
        "Split into declaration and assignment"
    ).sorted().toList();
    var actionTitles = codeActions.stream().map(it -> it.getRight().getTitle()).sorted().toList();
    Assert.assertEquals(expectedActions, actionTitles);

    var changeTypeAction = codeActions.stream()
        .map(Either::getRight)
        .filter(it -> it.getTitle().equals(invokedActionName))
        .findFirst()
        .orElseThrow();

    Gson gson = new GsonBuilder().create();
    changeTypeAction.setData(gson.fromJson(gson.toJson(changeTypeAction.getData()), JsonObject.class));

    final var resolvedAction = TestUtil.getNonBlockingEdt(server().getTextDocumentService().resolveCodeAction(changeTypeAction), 30000);

    final var edit = resolvedAction.getEdit();
    Assert.assertNotNull("resolved action must produce an edit", edit);
    Assert.assertFalse("edit must not be empty", edit.getChanges().isEmpty());
    Assert.assertEquals(1, edit.getChanges().size());

    final var after = """
        // code for code action tests
        class CodeActions {
          public static void f() {
            java.lang.String a = "";
            System.out.println();
          }
        }""";

    var file = PsiManager.getInstance(server().getProject()).findFile(Objects.requireNonNull(filePath.findVirtualFile()));
    assert file != null;

    Assert.assertEquals(after, TestUtil.applyEdits(file.getText(), edit.getChanges().get(filePath.toLspUri())));
  }
}
