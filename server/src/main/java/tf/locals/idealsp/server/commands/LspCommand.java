package tf.locals.idealsp.server.commands;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.ProjectService;
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class LspCommand<R> {
  private static final Logger LOG = Logger.getInstance(LspCommand.class);

  @NotNull
  protected abstract Supplier<@NotNull String> getMessageSupplier();

  protected abstract boolean isCancellable();

  protected abstract R execute(@NotNull ExecutorContext ctx);

  public @NotNull CompletableFuture<@Nullable R> runAsync(@NotNull Project project, @NotNull LspPath path) {
    final var virtualFile = path.refreshAndFindVirtualFile();
    if (virtualFile == null) {
      LOG.warn("File not found: " + path);
      return CompletableFuture.failedFuture(new RuntimeException("File not found: " + path));
    }

    return runAsyncWithCancel(project, cancelToken -> MiscUtil.produceWithPsiFileInReadAction(
        project, path, (psiFile) -> execute(new ExecutorContext(psiFile, project, cancelToken))));
  }

  public @NotNull CompletableFuture<@Nullable R> runAsync(@NotNull Project project) {
    return runAsyncWithCancel(project, cancelToken -> ApplicationManager.getApplication().runReadAction(
        (com.intellij.openapi.util.Computable<R>) () -> execute(new ExecutorContext(project, cancelToken))));
  }

  private @NotNull CompletableFuture<@Nullable R> runAsyncWithCancel(@NotNull Project project,
                                                                      @NotNull Function<@Nullable CancelChecker, R> action) {
    LOG.warn(getMessageSupplier().get());
    Executor executor = AppExecutorUtil.getAppExecutorService();
    if (isCancellable()) {
      return CompletableFutures.computeAsync(executor, cancelToken -> getResult(project, action, cancelToken));
    } else {
      return CompletableFuture.supplyAsync(() -> getResult(project, action, null), executor);
    }
  }

  private @Nullable R getResult(@NotNull Project project,
                                @NotNull Function<@Nullable CancelChecker, R> action,
                                @Nullable CancelChecker cancelToken) {
    ProjectService.getInstance().ensureImportFinished(project);
    final var result = new AtomicReference<R>(null);
    ApplicationManager.getApplication().invokeAndWait(() -> {
      try {
        result.set(action.apply(cancelToken));
      } catch (Throwable e) {
        LOG.warn("Command execution failed", e);
        throw e;
      }
    });
    return result.get();
  }
}
