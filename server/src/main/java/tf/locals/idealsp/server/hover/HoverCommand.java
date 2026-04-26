package tf.locals.idealsp.server.hover;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.commands.ExecutorContext;
import tf.locals.idealsp.server.commands.LspCommand;
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.function.Supplier;

public class HoverCommand extends LspCommand<Hover> {
  @NotNull
  private final Position pos;

  public HoverCommand(@NotNull Position pos) {
    this.pos = pos;
  }

  @Override
  protected @NotNull Supplier<@NotNull String> getMessageSupplier() {
    return () -> "Hover call";
  }

  @Override
  protected boolean isCancellable() {
    return true;
  }

  @Override
  protected Hover execute(@NotNull ExecutorContext ctx) {
    PsiFile file = ctx.getPsiFile();
    Document doc = MiscUtil.getDocument(file);
    if (doc == null) {
      return null;
    }

    int offset = MiscUtil.positionToOffset(doc, pos);
    var element = file.findElementAt(offset);

    if (element == null) {
      return null;
    }

    String text = element.getText();
    if (text == null || text.isEmpty()) {
      return null;
    }

    MarkupContent content = new MarkupContent(MarkupKind.PLAINTEXT, text);
    Range range = new Range(pos, pos);

    return new Hover(content, range);
  }
}