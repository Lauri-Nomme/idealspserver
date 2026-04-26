package tf.locals.idealsp.server.diagnostics;

import com.intellij.codeInsight.daemon.impl.*;
import com.intellij.codeInsight.multiverse.CodeInsightContextUtil;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.ProperTextRange;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tf.locals.idealsp.server.LspContext;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.util.Metrics;
import tf.locals.idealsp.server.util.MiscUtil;

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
  private static Diagnostic toDiagnostic(@NotNull HighlightInfo info, @NotNull Document doc,
                                         @NotNull QuickFixRegistry registry, @NotNull PsiFile file) {
    if (info.getDescription() == null)
      return null;

    final var range = MiscUtil.getRange(doc, info);
    var descriptors = new ArrayList<HighlightInfo.IntentionActionDescriptor>();
    info.findRegisteredQuickFix((intentionActionDescriptor, textRange) -> {
      descriptors.add(intentionActionDescriptor);
      return null;
    });
    // Force-initialize lazy ModCommandActionWrapper descriptors (IntelliJ 2026.1).
    // ModCommandActionWrapper.getText() returns "(not initialized) class X" until
    // isAvailable() is called, which populates myPresentation via getPresentation(ActionContext).
    // Some actions (e.g. WrapExpressionFix) remain "(not initialized)" even after force-init
    // because IntelliJ 2026.1 changed GenericsUtil.getVariableTypeByExpressionType to return
    // null for primitive types, leaving myExpectedType=null in the fix and making getPresentation()
    // always return null for those actions. Filter them out so the registry only stores usable fixes.
    var project = file.getProject();
    var validDescriptors = new ArrayList<HighlightInfo.IntentionActionDescriptor>();
    for (var descriptor : descriptors) {
      try {
        var action = descriptor.getAction();
        action.isAvailable(project, null, file);
        if (!action.getText().startsWith("(not initialized)")) {
          validDescriptors.add(descriptor);
        }
      } catch (Exception ignored) {
        validDescriptors.add(descriptor); // keep on error — don't silently discard
      }
    }
    registry.registerQuickFixes(range, validDescriptors);

    return new Diagnostic(range, info.getDescription(), diagnosticSeverity(info.getSeverity()), "ideals");
  }

  @Override
  public void run() {
    // Clear any lingering interrupt flag: a previous DiagnosticsTask on this pooled thread
    // may have been cancelled via ScheduledFuture.cancel(true), which sets the interrupt flag.
    // In IntelliJ 2026.1, runMainPasses checks the interrupt flag and throws InterruptedException
    // if it is set, producing zero diagnostics for the successor task.
    Thread.interrupted();

    String token = toString();

    var client = LspContext.getContext(file.getProject()).getClient();

    try {
      client.createProgress(new WorkDoneProgressCreateParams(Either.forLeft(token))).get(5, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception e) {
      LOG.warn("Failed to create progress: " + e.getMessage());
    }
    final var progressBegin = new WorkDoneProgressBegin();
    progressBegin.setTitle("Analyzing file...");
    progressBegin.setCancellable(false);
    progressBegin.setPercentage(0);
    client.notifyProgress(new ProgressParams(Either.forLeft(token), Either.forLeft(progressBegin)));

    try {
      var highlights = getHighlights(file, document);
      var diags = ReadAction.compute(() ->
          highlights.stream()
              .map(it -> toDiagnostic(it, document, session.getQuickFixRegistry(), file))
              .filter(Objects::nonNull)
              .collect(Collectors.toList())
      );
      client.publishDiagnostics(new PublishDiagnosticsParams(path.toLspUri(), diags));
    } catch (Exception e) {
      LOG.warn("Error in DiagnosticsTask.run(): " + e.getMessage());
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
    var project = psiFile.getProject();
    var context = ReadAction.compute(() -> CodeInsightContextUtil.getCodeInsightContext(psiFile));
    var colorsScheme = EditorColorsManager.getInstance().getGlobalScheme();
    var range = ProperTextRange.create(0, doc.getTextLength());

    var progress = new DaemonProgressIndicator();
    progress.start();

    return ProgressManager.getInstance().runProcess(() -> {
      var result = new ArrayList<HighlightInfo>();
      HighlightingSessionImpl.runInsideHighlightingSession(psiFile, context, colorsScheme, range, false, session -> {
        try {
          var highlights = DaemonCodeAnalyzerEx.getInstanceEx(project).runMainPasses(psiFile, doc, progress);
          if (highlights != null) {
            result.addAll(highlights);
          }
        } catch (Exception ex) {
          LOG.warn("runMainPasses error: " + ex.getMessage(), ex);
        }
      });
      return result;
    }, progress);
  }

  @NotNull
  private static DiagnosticSeverity diagnosticSeverity(@NotNull HighlightSeverity severity) {
    var result = severityMap.get(severity);
    return result != null ? result : DiagnosticSeverity.Hint;
  }
}