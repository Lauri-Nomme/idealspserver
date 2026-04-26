package tf.locals.idealsp.server.lsp;

import com.intellij.openapi.util.Ref;
import org.eclipse.lsp4j.*;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.TestUtil;
import tf.locals.idealsp.server.formatting.FormattingTestUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class FormattingTest extends LspServerTestBase {
  @Override
  protected String getProjectRelativePath() {
    return "formatting/formatting-project";
  }

  @Test
  public void wholeFileFormatting() {
    // Main.java: "class Main {\nint x = 10;\nint y = 20;\n}\n"
    // Java formatter adds 4-space indent to un-indented members
    final var expected = Set.of(
        TestUtil.newTextEdit(1, 0, 1, 0, "    "),
        TestUtil.newTextEdit(2, 0, 2, 0, "    ")
    );
    final var filePath = LspPath.fromLocalPath(getProjectPath().resolve("Main.java"));

    var params = new DocumentFormattingParams();
    params.setTextDocument(TestUtil.getDocumentIdentifier(filePath));
    params.setOptions(FormattingTestUtil.defaultOptions());

    checkFormattingResult(expected, server().getTextDocumentService().formatting(params));
  }

  @Test
  public void rangeFormatting() {
    // Range (0,0)-(2,0) covers lines 0-1; Java formatter expands to enclosing block,
    // so both member lines are formatted
    final var expected = Set.of(
        TestUtil.newTextEdit(1, 0, 1, 0, "    "),
        TestUtil.newTextEdit(2, 0, 2, 0, "    ")
    );
    final var filePath = LspPath.fromLocalPath(getProjectPath().resolve("Main.java"));

    var params = new DocumentRangeFormattingParams();
    params.setTextDocument(TestUtil.getDocumentIdentifier(filePath));
    params.setOptions(FormattingTestUtil.defaultOptions());
    params.setRange(new Range(new Position(0, 0), new Position(2, 0)));

    checkFormattingResult(expected, server().getTextDocumentService().rangeFormatting(params));
  }

  @Test
  public void onTypeFormatting() {
    // Util.java: "class Util {\nint x=   10           ;\n}\n"
    // Typing "}" at (2,1) triggers reformatting of the class body
    final var expected = Set.of(
        TestUtil.newTextEdit(1, 0, 1, 0, "    "),
        TestUtil.newTextEdit(1, 5, 1, 5, " "),
        TestUtil.newTextEdit(1, 7, 1, 9, ""),
        TestUtil.newTextEdit(1, 11, 1, 22, "")
    );
    final var filePath = LspPath.fromLocalPath(getProjectPath().resolve("Util.java"));

    var params = new DocumentOnTypeFormattingParams();
    params.setTextDocument(TestUtil.getDocumentIdentifier(filePath));
    params.setOptions(FormattingTestUtil.defaultOptions());
    params.setCh("}");
    params.setPosition(new Position(2, 1));

    checkFormattingResult(expected, server().getTextDocumentService().onTypeFormatting(params));
  }

  private void checkFormattingResult(@NotNull Set<? extends TextEdit> expected,
                                     @NotNull CompletableFuture<List<? extends TextEdit>> formattingResultFuture) {
    Ref<List<? extends TextEdit>> formattingResRef = new Ref<>();

    Assertions.assertDoesNotThrow(() -> formattingResRef.set(
        TestUtil.getNonBlockingEdt(formattingResultFuture, 3000)));
    Assertions.assertEquals(expected, new HashSet<>(formattingResRef.get()));
  }
}
