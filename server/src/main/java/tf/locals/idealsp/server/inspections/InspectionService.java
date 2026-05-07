package tf.locals.idealsp.server.inspections;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightingSessionImpl;
import com.intellij.codeInsight.multiverse.CodeInsightContextUtil;
import com.intellij.codeInspection.InspectionEP;
import com.intellij.codeInspection.InspectionProfile;
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.ex.InspectionToolsSupplier;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
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
            LOG.info("listInspections: query=" + query + ", count=" + result.size());
            return result;
        });
    }

    @SuppressWarnings("UnstableApiUsage")
    @NotNull
    public List<Diagnostic> runByName(@NotNull PsiFile psiFile, @NotNull String name) {
        Thread.interrupted();

        var doc = FileDocumentManager.getInstance().getDocument(psiFile.getVirtualFile());
        if (doc == null) {
            LOG.warn("runByName: no document");
            return List.of();
        }

        var project = psiFile.getProject();
        var context = ReadAction.compute(() -> CodeInsightContextUtil.getCodeInsightContext(psiFile));
        var colorsScheme = EditorColorsManager.getInstance().getGlobalScheme();
        var range = ProperTextRange.create(0, doc.getTextLength());

        var progress = new DaemonProgressIndicator();
        progress.start();

        var highlights = ProgressManager.getInstance().runProcess(() -> {
            var result = new ArrayList<HighlightInfo>();
            HighlightingSessionImpl.runInsideHighlightingSession(psiFile, context, colorsScheme, range, false, session -> {
                try {
                    var all = DaemonCodeAnalyzerEx.getInstanceEx(project).runMainPasses(psiFile, doc, progress);
                    if (all != null) result.addAll(all);
                } catch (Exception ex) {
                    LOG.warn("runMainPasses error: " + ex.getMessage(), ex);
                }
            });
            return result;
        }, progress);

        var matched = 0;
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
            matched++;
        }
        LOG.info("runByName: " + name + " matched " + matched + " of " + highlights.size() + " highlights");
        return diags;
    }
}
