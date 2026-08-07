package tf.locals.idealsp.server.diagnostics;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.ProjectService;
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service(Service.Level.PROJECT)
final public class DiagnosticsService {
  private static final Logger LOG = Logger.getInstance(DiagnosticsService.class);
  public static final int DELAY = 200;  // debounce delay ms -- massive updates one character each are typical when typing

  @NotNull
  private final Project project;

  private final ConcurrentHashMap<LspPath, FileDiagnosticsState> states = new ConcurrentHashMap<>();

  public DiagnosticsService(@NotNull Project project) {
    this.project = project;
  }

  public void launchDiagnostics(@NotNull LspPath path) {
    if (ProjectService.getInstance().isImportInProgressOrWritePending(project)) {
      // Skip while the project is importing/syncing: acquiring a read permit here (and later
      // running daemon highlighting) collides with the sync's workspace-model write upgrade
      // and can wedge the server (see prd/document-symbols-rca.md). A later didChange/didSave/
      // didOpen re-triggers diagnostics.
      LOG.warn("Deferring diagnostics for " + path + " while project is importing/syncing");
      return;
    }
    MiscUtil.invokeWithPsiFileInReadAction(project, path, (psiFile) -> {
      final var document = MiscUtil.getDocument(psiFile);
      if (document == null) {
        LOG.error("document not found: " + path);
        return;
      }
      Optional.ofNullable(states.put(path, launchDiagnostic(path, psiFile, document)))
          .ifPresent(FileDiagnosticsState::halt);
    });
  }

  public void haltDiagnostics(@NotNull LspPath path) {
    Optional.ofNullable(states.remove(path)).ifPresent(FileDiagnosticsState::halt);
  }

  /**
   * Halts and removes all tracked diagnostic states.
   * Intended for test teardown to prevent cross-test state pollution
   * when different tests share the same virtual file paths (e.g. temp:///src/test.java).
   */
  public void resetForTesting() {
    var snapshot = List.copyOf(states.values());
    states.clear();
    snapshot.forEach(FileDiagnosticsState::halt);
  }

  @NotNull
  public List<HighlightInfo.IntentionActionDescriptor> getQuickFixes(@NotNull LspPath path, @NotNull Range range) {
    return Optional.ofNullable(states.get(path))
        .map(it -> it.getQuickFixes().collectForRange(range))
        .orElse(Collections.emptyList());
  }

  /**
   * Waits (non-blocking on the EDT) until the diagnostics/highlighting task for {@code path}
   * has completed, so quick fixes are available before code actions are collected. Returns
   * immediately if no diagnostics task is registered. Call from a background thread.
   */
  public void waitForDiagnosticsReady(@NotNull LspPath path, long timeoutMs) {
    var state = states.get(path);
    if (state == null) {
      // No task yet — trigger one so quick fixes become available.
      launchDiagnostics(path);
      state = states.get(path);
    }
    if (state == null) return;
    var deadline = System.currentTimeMillis() + timeoutMs;
    while (!state.isDone() && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
  @NotNull
  private FileDiagnosticsState launchDiagnostic(@NotNull LspPath path,
                                                @NotNull PsiFile psiFile,
                                                @NotNull Document doc) {

    var quickFixes = new QuickFixRegistry();

    final var session = new DiagnosticsTask(path, psiFile, doc, new DiagnosticSession() {
      @Override
      public @NotNull QuickFixRegistry getQuickFixRegistry() {
        return quickFixes;
      }

      @Override
      public boolean isOutdated() {
        return quickFixes != Optional.ofNullable(states.get(path))
            .map(FileDiagnosticsState::getQuickFixes)
            .orElse(null);
      }

      @Override
      public void signalRestart() {
        launchDiagnostics(path);
      }
    });

    var task = AppExecutorUtil.getAppScheduledExecutorService().schedule(
        session, DELAY, TimeUnit.MILLISECONDS);

    return new FileDiagnosticsState(psiFile, quickFixes, task);

  }
}
