package tf.locals.idealsp.server.diagnostics;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ScheduledFuture;

final class FileDiagnosticsState {
  private final @NotNull PsiFile file;
  private final @NotNull QuickFixRegistry quickFixes;
  private final @NotNull ScheduledFuture<?> task;

  public FileDiagnosticsState(@NotNull PsiFile file, @NotNull QuickFixRegistry quickFixes, @NotNull ScheduledFuture<?> task) {
    this.file = file;
    this.task = task;
    this.quickFixes = quickFixes;
  }

  void halt() {
    // to initiate ProcessCancelledException in a running highlighting
    DaemonCodeAnalyzer.getInstance(file.getProject()).restart(file);

    // cancel(false): do not interrupt a running task via thread interrupt flag.
    // cancel(true) causes InterruptedException inside DumbService.runReadActionInSmartMode
    // (via runBlockingWithParallelismCompensation) when the task is mid-highlighting,
    // which results in 0 diagnostics being published instead of the correct set.
    task.cancel(false);
  }

  public @NotNull QuickFixRegistry getQuickFixes() {
    return quickFixes;
  }
}
