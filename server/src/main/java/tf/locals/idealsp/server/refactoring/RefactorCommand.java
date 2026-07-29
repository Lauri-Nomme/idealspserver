package tf.locals.idealsp.server.refactoring;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.codeactions.RefactoringHandler;
import tf.locals.idealsp.server.commands.ExecutorContext;
import tf.locals.idealsp.server.commands.LspCommand;
import tf.locals.idealsp.server.util.EditorUtil;
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.function.Supplier;

public class RefactorCommand extends LspCommand<RefactorResult> {
  private static final Logger LOG = Logger.getInstance(RefactorCommand.class);
  private final RefactorParams params;

  public RefactorCommand(RefactorParams params) {
    this.params = params;
  }

  @Override
  protected @NotNull Supplier<@NotNull String> getMessageSupplier() {
    return () -> "Refactor " + params.getType() + " at " + params.getPosition();
  }

  @Override
  protected boolean isCancellable() {
    return false;
  }

  @Override
  protected @Nullable RefactorResult execute(@NotNull ExecutorContext ctx) {
    final var file = ctx.getPsiFile();

    var disposable = com.intellij.openapi.util.Disposer.newDisposable();
    try {
      var resultRef = new com.intellij.openapi.util.Ref<RefactorResult>();
      var startRange = params.getStartRange();
      var endRange = params.getEndRange();
      boolean hasRange = startRange != null && endRange != null;
      EditorUtil.withEditor(disposable, file, params.getPosition(),
          hasRange ? startRange : null, hasRange ? endRange : null, editor -> {
        if (editor == null) {
          resultRef.set(new RefactorResult(params.getType(), false, "editor not available"));
          return;
        }
        boolean ok = dispatch(editor, ctx.getProject(), file);
        resultRef.set(new RefactorResult(params.getType(), ok, ok ? null : "refactoring failed"));
      });
      return resultRef.get();
    } catch (Exception e) {
      LOG.warn("RefactorCommand error: " + e, e);
      return new RefactorResult(params.getType(), false, e.getMessage());
    } finally {
      com.intellij.openapi.util.Disposer.dispose(disposable);
    }
  }

  private boolean dispatch(Editor editor, Project project, PsiFile file) {
    return switch (params.getType()) {
      case "extract-method" -> RefactoringHandler.applyExtractMethod(project, editor, file, params.getName());
      case "introduce-variable" -> RefactoringHandler.applyIntroduceVariable(project, editor, file);
      case "inline" -> RefactoringHandler.applyInline(project, editor, file);
      case "move" -> RefactoringHandler.applyMove(project, editor, file, params.getTargetPackageUri());
      case "safe-delete" -> RefactoringHandler.applySafeDelete(project, editor, file);
      default -> {
        LOG.warn("unknown refactor type: " + params.getType());
        yield false;
      }
    };
  }
}
