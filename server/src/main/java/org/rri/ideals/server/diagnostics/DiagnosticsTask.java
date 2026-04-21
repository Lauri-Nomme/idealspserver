package org.rri.ideals.server.diagnostics;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightingSessionImpl;
import com.intellij.codeInsight.multiverse.CodeInsightContext;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.util.ProperTextRange;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rri.ideals.server.LspContext;
import org.rri.ideals.server.LspPath;
import org.rri.ideals.server.util.Metrics;
import org.rri.ideals.server.util.MiscUtil;

import java.util.*;
import java.util.stream.Collectors;

class DiagnosticsTask implements Runnable {
  private final static Logger LOG = Logger.getInstance(DiagnosticsTask.class);

  @NotNull
  private final PsiFile file;
  @NotNull
  private final Document document;

  private static final Map<HighlightSeverity, DiagnosticSeverity> severityMap = Map.of(
      HighlightSeverity.INFORMATION, DiagnosticSeverity.Information,
      HighlightSeverity.WARNING, DiagnosticSeverity.Warning,
      HighlightSeverity.ERROR, DiagnosticSeverity.Error
  );

  @NotNull
  private final DiagnosticSession session;

  @NotNull
  private final LspPath path;

  public DiagnosticsTask(@NotNull LspPath path, @NotNull PsiFile file, @NotNull Document document, @NotNull DiagnosticSession session) {
    this.path = path;
    this.file = file;
    this.document = document;
    this.session = session;
  }

  @Nullable
  private static Diagnostic toDiagnostic(@NotNull HighlightInfo info, @NotNull Document doc, @NotNull QuickFixRegistry registry) {
    if (info.getDescription() == null)
      return null;

    final var range = MiscUtil.getRange(doc, info);
    var descriptors = new ArrayList<HighlightInfo.IntentionActionDescriptor>();
    info.findRegisteredQuickFix((intentionActionDescriptor, textRange) -> {
      descriptors.add(intentionActionDescriptor);
      return null;
    });
    registry.registerQuickFixes(range, descriptors);

    return new Diagnostic(range, info.getDescription(), diagnosticSeverity(info.getSeverity()), "ideals");
  }

  @Override
  public void run() {
    String token = toString();

    var client = LspContext.getContext(file.getProject()).getClient();

    client.createProgress(new WorkDoneProgressCreateParams(Either.forLeft(token))).join();
    final var progressBegin = new WorkDoneProgressBegin();
    progressBegin.setTitle("Analyzing file...");
    progressBegin.setCancellable(false);
    progressBegin.setPercentage(0);
    client.notifyProgress(new ProgressParams(Either.forLeft(token), Either.forLeft(progressBegin)));

    try {
      var highlights = getHighlights(file, document);
      var diags = ReadAction.compute(() ->
          highlights.stream()
              .map(it -> toDiagnostic(it, document, session.getQuickFixRegistry()))
              .filter(Objects::nonNull)
              .collect(Collectors.toList())
      );
      client.publishDiagnostics(new PublishDiagnosticsParams(path.toLspUri(), diags));
    } finally {
      client.notifyProgress(new ProgressParams(Either.forLeft(token), Either.forLeft(new WorkDoneProgressEnd())));
    }
  }

  @NotNull
  private List<HighlightInfo> getHighlights(@NotNull PsiFile file, @NotNull Document doc) {
    return Metrics.call(
        () -> "Analyzing file: " + file.getVirtualFile(),
        () -> doHighlighting(doc, file)
    );
  }

  @SuppressWarnings("UnstableApiUsage")
  @NotNull
  private List<HighlightInfo> doHighlighting(@NotNull Document doc, @NotNull PsiFile psiFile) {

    var progress = new DaemonProgressIndicator();

    var project = psiFile.getProject();

    final var resultRef = new java.util.concurrent.atomic.AtomicReference<List<HighlightInfo>>();

    return ProgressManager.getInstance().runProcess(() -> {

      try {
        final var range = ProperTextRange.create(0, doc.getTextLength());

        // IntelliJ 2026.1+: Must create HighlightingSession with CodeInsightContext
        // Use reflection to avoid compilation issues with version-specific API
        CodeInsightContext context;
        try {
          context = (CodeInsightContext) Class.forName("com.intellij.codeInsight.multiverse.CodeInsightContexts")
              .getMethod("defaultContext")
              .invoke(null);
        } catch (Exception e) {
          LOG.warn("Could not get CodeInsightContext: " + e.getMessage());
          return Collections.emptyList();
        }

        var colorsScheme = EditorColorsManager.getInstance().getGlobalScheme();

        // Must run runMainPasses INSIDE the HighlightingSession callback
        HighlightingSessionImpl.runInsideHighlightingSession(
            psiFile,
            context,
            colorsScheme,
            range,
            false,
            session -> {
              try {
                var result = DaemonCodeAnalyzerEx.getInstanceEx(project).runMainPasses(psiFile, doc, progress);
                resultRef.set(result);
              } catch (Exception e) {
                LOG.warn("Highlighting error: " + e.getMessage());
                resultRef.set(Collections.emptyList());
              }
            }
        );

        var result = resultRef.get();
        if (LOG.isTraceEnabled()) LOG.trace("Analyzing file: produced items: " + (result != null ? result.size() : 0));
        return result != null ? result : Collections.emptyList();
      } catch (IndexNotReadyException e) {
        LOG.warn("Analyzing file: index not ready");
        return Collections.emptyList();
      } catch (ProcessCanceledException e) {
        if (LOG.isTraceEnabled()) LOG.trace("Analyzing file: highlighting has been cancelled: " + file.getVirtualFile());

        if (!session.isOutdated())
          session.signalRestart();

        throw e;
      }

    }, progress);
  }

  @NotNull
  private static DiagnosticSeverity diagnosticSeverity(@NotNull HighlightSeverity severity) {
    var result = severityMap.get(severity);
    return result != null ? result : DiagnosticSeverity.Hint;
  }
}
