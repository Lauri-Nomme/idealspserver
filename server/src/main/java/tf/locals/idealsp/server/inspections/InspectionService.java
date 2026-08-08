package tf.locals.idealsp.server.inspections;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightingSessionImpl;
import com.intellij.codeInsight.multiverse.CodeInsightContextUtil;
import com.intellij.codeInspection.InspectionEP;
import com.intellij.codeInspection.InspectionProfile;
import com.intellij.codeInspection.ex.*;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentIterator;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.psi.PsiManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.ProperTextRange;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service(Service.Level.PROJECT)
final public class InspectionService {

    private static final Logger LOG = Logger.getInstance(InspectionService.class);

    private static final Map<HighlightSeverity, DiagnosticSeverity> severityMap = Map.of(
            HighlightSeverity.INFORMATION, DiagnosticSeverity.Information,
            HighlightSeverity.WARNING, DiagnosticSeverity.Warning,
            HighlightSeverity.ERROR, DiagnosticSeverity.Error
    );

    @NotNull
    private final Project project;

    public InspectionService(@NotNull Project project) {
        this.project = project;
    }

    @NotNull
    public List<InspectionInfo> listInspections(@NotNull String query) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<InspectionInfo>>) () -> {
            @SuppressWarnings("removal")
            InspectionProfile profile = InspectionProjectProfileManager.getInstance(project).getInspectionProfile();
            var eps = InspectionEP.GLOBAL_INSPECTION.getExtensionList();
            var result = new ArrayList<InspectionInfo>();
            var lowerQuery = query.toLowerCase();
            var seen = new HashSet<String>();

            for (var ep : eps) {
                var shortName = ep.shortName;
                if (shortName == null || shortName.isEmpty() || !seen.add(shortName)) continue;
                var displayName = ep.displayName;
                if (!query.isEmpty()) {
                    var matchesShortName = shortName.toLowerCase().contains(lowerQuery);
                    var matchesDisplayName = displayName != null && displayName.toLowerCase().contains(lowerQuery);
                    if (!matchesShortName && !matchesDisplayName) continue;
                }
                try {
                    boolean enabled;
                    try {
                        var tool = profile.getInspectionTool(shortName, project);
                        enabled = tool != null && profile.isToolEnabled(tool.getDisplayKey());
                    } catch (Exception e) { enabled = ep.enabledByDefault; }
                    String group = ep.groupDisplayName != null && !ep.groupDisplayName.isEmpty()
                            ? ep.groupDisplayName : ep.groupPath;
                    String desc = "";
                    try { var inst = ep.getInstance(); desc = inst != null ? inst.getStaticDescription() : ""; }
                    catch (Exception ignored) {}
                    result.add(new InspectionInfo(shortName,
                            displayName != null ? displayName : shortName,
                            group != null ? group : "", enabled,
                            desc != null ? desc : ""));
                } catch (Exception e) {
                    LOG.warn("Failed to load info: " + shortName, e);
                }
            }
            LOG.warn("listInspections: query=" + query + ", count=" + result.size());
            return result;
        });
    }

    @SuppressWarnings("UnstableApiUsage")
    @NotNull
    public List<Diagnostic> runByName(@NotNull PsiFile psiFile, @NotNull String name) {
        InspectionToolWrapper<?, ?> wrapper = lookupWrapper(name);
        if (wrapper == null) {
            LOG.warn("runByName: inspection not found: " + name);
            return List.of();
        }

        var doc = FileDocumentManager.getInstance().getDocument(psiFile.getVirtualFile());
        if (doc == null) {
            LOG.warn("runByName: no document");
            return List.of();
        }

        // Build a profile with only the requested tool
        InspectionProfileImpl rootProfile = InspectionProfileManager.getInstance(project).getCurrentProfile();
        var singleProfile = new InspectionProfileImpl(
                wrapper.getDisplayName(),
                new InspectionToolsSupplier.Simple(Collections.singletonList(wrapper)),
                rootProfile);
        try {
            singleProfile.enableTool(wrapper.getShortName(), project);
        } catch (Exception e) {
            LOG.warn("runByName: profile setup failed for " + name + ", falling back to filter mode", e);
            singleProfile = null;
        }
        final InspectionProfileImpl effectiveProfile = singleProfile;

        var context = ReadAction.compute(() -> CodeInsightContextUtil.getCodeInsightContext(psiFile));
        var colorsScheme = EditorColorsManager.getInstance().getGlobalScheme();
        var range = ProperTextRange.create(0, doc.getTextLength());

        var progress = new DaemonProgressIndicator();
        progress.start();

        var diagsRef = new AtomicReference<List<Diagnostic>>();
        ProgressManager.getInstance().runProcess(() -> {
            Runnable highlightRunner = () -> {
                var highlights = new ArrayList<HighlightInfo>();
                HighlightingSessionImpl.runInsideHighlightingSession(psiFile, context, colorsScheme, range, false, session -> {
                    try {
                        var all = DaemonCodeAnalyzerEx.getInstanceEx(project).runMainPasses(psiFile, doc, progress);
                        if (all != null) highlights.addAll(all);
                    } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                        // control-flow exception — rethrow, do not log it as an error
                        throw pce;
                    } catch (Exception ex) {
                        LOG.warn("runMainPasses error", ex);
                    }
                });
                diagsRef.set(extractDiagnostics(highlights, doc, name));
            };

            if (effectiveProfile != null) {
                try {
                    InspectionProfileWrapper.runWithCustomInspectionWrapper(psiFile,
                            __ -> new InspectionProfileWrapper(effectiveProfile),
                            highlightRunner);
                } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                    throw pce;
                } catch (Exception e) {
                    LOG.warn("runByName: single-profile daemon failed for " + name + ", falling back to filter mode", e);
                    highlightRunner.run();
                }
            } else {
                highlightRunner.run();
            }
        }, progress);

        var result = diagsRef.get() != null ? diagsRef.get() : List.<Diagnostic>of();
        LOG.warn("runByName: " + name + " found " + result.size() + " problems" +
                (effectiveProfile != null ? " [single-tool profile]" : " [filter mode]"));
        return result;
    }

    @NotNull
    public List<Diagnostic> runByNameOnAllFiles(@NotNull String name) {
        LOG.warn("runByNameOnAllFiles: starting for inspection '" + name + "'");
        InspectionToolWrapper<?, ?> wrapper = lookupWrapper(name);
        if (wrapper == null) {
            LOG.warn("runByNameOnAllFiles: inspection not found: " + name);
            return List.of();
        }

        InspectionProfileImpl rootProfile = InspectionProfileManager.getInstance(project).getCurrentProfile();
        var singleProfile = new InspectionProfileImpl(
                wrapper.getDisplayName(),
                new InspectionToolsSupplier.Simple(Collections.singletonList(wrapper)),
                rootProfile);
        final InspectionProfileImpl effectiveProfile = singleProfile;

        var allDiags = new ArrayList<Diagnostic>();
        var fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
        var psiManager = PsiManager.getInstance(project);
        LOG.warn("runByNameOnAllFiles: iterating files in project content");

        // Headless daemon passes can block far longer than any single file should take:
        // ExternalToolPass.getInfos busy-waits up to 60s per file for annotator results that
        // never arrive once the inspection itself saturates the app executor pool (which also
        // drains the MergingUpdateQueue that completes those annotators). An unbounded
        // project-wide run therefore grinds for minutes per file and wedges the server for
        // every request after it. Bound the whole run with a wall-clock budget: when it is
        // exceeded a watchdog thread cancels the shared progress indicator, the running pass
        // throws ProcessCanceledException at its next checkCanceled() call, and the loop
        // stops, returning partial results.
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        var currentProgress = new java.util.concurrent.atomic.AtomicReference<DaemonProgressIndicator>();
        long runStartNanos = System.nanoTime();
        long budgetMs = 8_000L;
        long budgetNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budgetMs);

        var watchdog = new Thread(() -> {
            try {
                while (!cancelled.get() && System.nanoTime() - runStartNanos < budgetNanos) {
                    Thread.sleep(100);
                }
                if (cancelled.get()) return;
                LOG.warn("runByNameOnAllFiles: budget (" + budgetMs + "ms) exceeded, cancelling inspection");
                cancelled.set(true);
                var p = currentProgress.get();
                if (p != null) p.cancel();
            } catch (InterruptedException ignored) {
            }
        }, "idealsp-inspection-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        var filesProcessed = new java.util.concurrent.atomic.AtomicInteger(0);
        try {
            fileIndex.iterateContent((ContentIterator) fileOrDir -> {
                if (cancelled.get()) return false;
                ProgressManager.checkCanceled();
                if (fileOrDir.isDirectory()) return true;

                PsiFile psiFile = ReadAction.compute(() -> psiManager.findFile(fileOrDir));
                if (psiFile == null) return true;

                // Only run on files the inspection targets. Running the full daemon on other
                // languages (e.g. TypeScript) makes their annotators query external language
                // services (JS/TS service queue) that do not answer in headless mode, blocking
                // each such file for 20s and turning the whole loop into an endless grind.
                if (!isSupportedFile(psiFile, wrapper)) return true;

                var doc = FileDocumentManager.getInstance().getDocument(fileOrDir);
                if (doc == null) return true;

                var context = ReadAction.compute(() -> CodeInsightContextUtil.getCodeInsightContext(psiFile));
                var colorsScheme = EditorColorsManager.getInstance().getGlobalScheme();
                var range = ProperTextRange.create(0, doc.getTextLength());

                var progress = new DaemonProgressIndicator();
                progress.start();
                currentProgress.set(progress);
                try {
                    var diagsRef = new AtomicReference<List<Diagnostic>>();
                    ProgressManager.getInstance().runProcess(() -> {
                        Runnable highlightRunner = () -> {
                            var highlights = new ArrayList<HighlightInfo>();
                            HighlightingSessionImpl.runInsideHighlightingSession(psiFile, context, colorsScheme, range, false, session -> {
                                try {
                                    var all = DaemonCodeAnalyzerEx.getInstanceEx(project).runMainPasses(psiFile, doc, progress);
                                    if (all != null) highlights.addAll(all);
                                } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                                    // control-flow exception — abort the whole inspection rather
                                    // than grinding file-by-file through long annotator waits
                                    throw pce;
                                } catch (Exception ex) {
                                    LOG.warn("runMainPasses error", ex);
                                }
                            });
                            diagsRef.set(extractDiagnostics(highlights, doc, name));
                        };

                        if (effectiveProfile != null) {
                            try {
                                InspectionProfileWrapper.runWithCustomInspectionWrapper(psiFile,
                                        __ -> new InspectionProfileWrapper(effectiveProfile),
                                        highlightRunner);
                            } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                                throw pce;
                            } catch (Exception e) {
                                LOG.warn("runByNameOnAllFiles: single-profile daemon failed for " + name + ", falling back to filter mode", e);
                                highlightRunner.run();
                            }
                        } else {
                            highlightRunner.run();
                        }
                    }, progress);

                    var result = diagsRef.get();
                    if (result != null) allDiags.addAll(result);
                    filesProcessed.incrementAndGet();
                } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                    // budget watchdog fired — stop the loop and return partial results
                    cancelled.set(true);
                    LOG.warn("runByNameOnAllFiles: cancelled after " + filesProcessed.get() + " files, returning partial results");
                } finally {
                    currentProgress.set(null);
                }
                return !cancelled.get();
            });
        } finally {
            cancelled.set(true);
        }

        LOG.warn("runByNameOnAllFiles: processed " + filesProcessed.get() + " files, found " + allDiags.size() + " problems across all files");
        return allDiags;
    }

    /**
     * Whether the inspection run should process {@code psiFile}. The all-files inspection
     * runs the full daemon pass on each file, which executes every annotator of the file's
     * language; on non-Java files (TypeScript, etc.) those annotators query external language
     * services that are unavailable in headless mode and block for 20s each. Such files are
     * skipped so the project-wide run stays bounded.
     */
    private static boolean isSupportedFile(@NotNull PsiFile psiFile, @NotNull InspectionToolWrapper<?, ?> wrapper) {
        var tool = wrapper.getTool();
        if (tool instanceof com.intellij.codeInspection.LocalInspectionTool local) {
            try {
                if (!local.isAvailableForFile(psiFile)) return false;
            } catch (Exception ignored) {}
        }
        return psiFile.getFileType() == com.intellij.ide.highlighter.JavaFileType.INSTANCE;
    }

    @NotNull
    private List<Diagnostic> extractDiagnostics(@NotNull List<HighlightInfo> highlights,
                                                 com.intellij.openapi.editor.Document doc,
                                                 @NotNull String name) {
        var diags = new ArrayList<Diagnostic>();
        for (var info : highlights) {
            var toolId = info.getInspectionToolId();
            if (toolId == null || !toolId.equals(name)) continue;
            if (info.getDescription() == null) continue;

            var sl = doc.getLineNumber(info.startOffset);
            var sc = info.startOffset - doc.getLineStartOffset(sl);
            var el = doc.getLineNumber(info.endOffset);
            var ec = info.endOffset - doc.getLineStartOffset(el);

            var severity = severityMap.getOrDefault(info.getSeverity(), DiagnosticSeverity.Hint);
            var diag = new Diagnostic(
                    new Range(new Position(sl, sc), new Position(el, ec)),
                    info.getDescription()
            );
            diag.setSeverity(severity);
            diag.setCode(toolId);
            diags.add(diag);
        }
        return diags;
    }

    private @org.jetbrains.annotations.Nullable InspectionToolWrapper<?, ?> lookupWrapper(@NotNull String name) {
        @SuppressWarnings("removal")
        InspectionProfile profile = InspectionProjectProfileManager.getInstance(project).getInspectionProfile();
        InspectionToolWrapper<?, ?> tool = profile.getInspectionTool(name, project);
        if (tool != null) return tool;
        for (var ep : InspectionEP.GLOBAL_INSPECTION.getExtensionList()) {
            if (name.equals(ep.shortName)) {
                var instance = ep.getInstance();
                if (instance instanceof com.intellij.codeInspection.LocalInspectionTool local)
                    return new LocalInspectionToolWrapper(local);
                if (instance instanceof com.intellij.codeInspection.GlobalInspectionTool global)
                    return new GlobalInspectionToolWrapper(global);
            }
        }
        return null;
    }
}
