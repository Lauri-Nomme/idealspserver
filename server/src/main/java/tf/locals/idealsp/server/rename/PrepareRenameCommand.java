package tf.locals.idealsp.server.rename;

import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tf.locals.idealsp.server.commands.ExecutorContext;
import tf.locals.idealsp.server.commands.LspCommand;
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.function.Supplier;

public class PrepareRenameCommand extends LspCommand<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> {
  private final Position pos;

  public PrepareRenameCommand(Position pos) {
    this.pos = pos;
  }

  @Override
  protected @NotNull Supplier<@NotNull String> getMessageSupplier() {
    return () -> "Prepare rename at " + pos;
  }

  @Override
  protected boolean isCancellable() {
    return false;
  }

  @Override
  protected @Nullable Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior> execute(@NotNull ExecutorContext ctx) {
    final var file = ctx.getPsiFile();
    Document doc = MiscUtil.getDocument(file);
    if (doc == null) return null;

    var element = findRenameElement(file, doc, pos);
    if (element == null) return null;

    var range = psiElementToRange(doc, element);
    if (range == null) return null;

    return Either3.forSecond(new PrepareRenameResult(range, element.getText()));
  }

  private static @Nullable PsiElement findRenameElement(@NotNull PsiFile file, @NotNull Document doc, @NotNull Position pos) {
    int offset = MiscUtil.positionToOffset(doc, pos);
    PsiElement element = file.findElementAt(offset);
    if (element == null) return null;

    while (element != null) {
      var processor = RenamePsiElementProcessor.forElement(element);
      if (processor.canProcessElement(element)) {
        return element;
      }
      element = element.getParent();
    }
    return null;
  }

  private static @Nullable Range psiElementToRange(@NotNull Document doc, @NotNull PsiElement element) {
    var textRange = element.getTextRange();
    if (textRange == null) return null;
    return new Range(
        MiscUtil.offsetToPosition(doc, textRange.getStartOffset()),
        MiscUtil.offsetToPosition(doc, textRange.getEndOffset())
    );
  }
}
