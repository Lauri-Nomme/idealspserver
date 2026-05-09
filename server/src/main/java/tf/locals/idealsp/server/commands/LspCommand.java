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
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
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
      LOG.info("File not found: " + path);
      return CompletableFuture.failedFuture(new RuntimeException("File not found: " + path));
    }

    LOG.info(getMessageSupplier().get());
    Executor executor = AppExecutorUtil.getAppExecutorService();
    if (isCancellable()) {
      return CompletableFutures.computeAsync(executor, cancelToken -> getResult(path, project, cancelToken));
    } else {
      return CompletableFuture.supplyAsync(() -> getResult(path, project, null), executor);
    }
  }

  private @Nullable R getResult(@NotNull LspPath path,
                                @NotNull Project project,
                                @Nullable CancelChecker cancelToken) {
    final AtomicReference<R> ref = new AtomicReference<>();
    ApplicationManager.getApplication()
        .invokeAndWait(
            () -> ref.set(MiscUtil.produceWithPsiFileInReadAction(
                project,
                path,
                (psiFile) -> execute(new ExecutorContext(psiFile, project, cancelToken))
            )));
    return ref.get();
  }
}
