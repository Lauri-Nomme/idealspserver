package tf.locals.idealsp.server.inspections;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInspection.InspectionEP;
import com.intellij.codeInspection.InspectionEngine;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.InspectionProfile;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ex.GlobalInspectionContextBase;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Service(Service.Level.PROJECT)
final public class InspectionService {

    private static final Logger LOG = Logger.getInstance(InspectionService.class);

    private static final Map<String, DiagnosticSeverity> typeToSeverity = Map.ofEntries(
            Map.entry("ERROR", DiagnosticSeverity.Error),
            Map.entry("GENERIC_ERROR", DiagnosticSeverity.Error),
            Map.entry("LIKE_UNKNOWN_SYMBOL", DiagnosticSeverity.Error),
            Map.entry("GENERIC_ERROR_OR_WARNING", DiagnosticSeverity.Warning),
            Map.entry("WARNING", DiagnosticSeverity.Warning),
            Map.entry("WEAK_WARNING", DiagnosticSeverity.Warning),
            Map.entry("LIKE_DEPRECATED", DiagnosticSeverity.Warning),
            Map.entry("LIKE_UNUSED_SYMBOL", DiagnosticSeverity.Warning),
            Map.entry("LIKE_MARKED_FOR_REMOVAL", DiagnosticSeverity.Warning),
            Map.entry("POSSIBLE_PROBLEM", DiagnosticSeverity.Warning)
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
                if (shortName == null || shortName.isEmpty() || !seen.add(shortName)) {
                    continue;
                }
                var displayName = ep.displayName;

                if (!query.isEmpty()) {
                    var matchesShortName = shortName.toLowerCase().contains(lowerQuery);
                    var matchesDisplayName = displayName != null && displayName.toLowerCase().contains(lowerQuery);
                    if (!matchesShortName && !matchesDisplayName) {
                        continue;
                    }
                }

                try {
                    boolean enabled;
                    try {
                        var tool = profile.getInspectionTool(shortName, project);
                        enabled = tool != null && profile.isToolEnabled(tool.getDisplayKey());
                    } catch (Exception e) {
                        enabled = ep.enabledByDefault;
                    }

                    String group = ep.groupDisplayName;
                    if (group == null || group.isEmpty()) {
                        group = ep.groupPath;
                    }

                    String desc = "";
                    try {
                        var instance = ep.getInstance();
                        desc = instance != null ? instance.getStaticDescription() : "";
                    } catch (Exception ignored) {
                    }

                    result.add(new InspectionInfo(
                            shortName,
                            displayName != null ? displayName : shortName,
                            group != null ? group : "",
                            enabled,
                            desc != null ? desc : ""
                    ));
                } catch (Exception e) {
                    LOG.warn("Failed to load info for inspection: " + shortName, e);
                }
            }

            LOG.info("listInspections: query=" + query + ", tools count=" + result.size());
            return result;
        });
    }

    @NotNull
    public List<Diagnostic> runByName(@NotNull PsiFile psiFile, @NotNull String name) {
        InspectionToolWrapper<?, ?> wrapper = lookupWrapper(name);
        if (wrapper == null) {
            LOG.warn("runByName: inspection not found: " + name);
            return List.of();
        }

        InspectionManager manager = InspectionManager.getInstance(project);
        GlobalInspectionContextBase context = (GlobalInspectionContextBase) manager.createNewGlobalContext(false);
        context.setCurrentScope(new AnalysisScope(psiFile));

        List<ProblemDescriptor> descriptors;
        try {
            if (wrapper.getTool() instanceof com.intellij.codeInspection.GlobalSimpleInspectionTool simple) {
                ProblemsHolder holder = new ProblemsHolder(manager, psiFile, false);
                simple.checkFile(psiFile, manager, holder, context, null);
                descriptors = new ArrayList<>(holder.getResults());
            } else {
                descriptors = ApplicationManager.getApplication()
                        .runReadAction((Computable<List<ProblemDescriptor>>) () ->
                                InspectionEngine.runInspectionOnFile(psiFile, wrapper, context));
            }
        } catch (Exception e) {
            LOG.warn("runByName: error running " + name + ": " + e.getMessage(), e);
            return List.of();
        }

        LOG.info("runByName: " + name + " produced " + descriptors.size() + " descriptors");
        return toDiagnostics(descriptors, psiFile);
    }

    private @org.jetbrains.annotations.Nullable InspectionToolWrapper<?, ?> lookupWrapper(@NotNull String name) {
        @SuppressWarnings("removal")
        InspectionProfile profile = InspectionProjectProfileManager.getInstance(project).getInspectionProfile();
        InspectionToolWrapper<?, ?> tool = profile.getInspectionTool(name, project);
        if (tool != null) {
            LOG.info("lookupWrapper: found " + name + " via profile as " + tool.getClass().getName());
            return tool;
        }

        for (var ep : InspectionEP.GLOBAL_INSPECTION.getExtensionList()) {
            if (name.equals(ep.shortName)) {
                var instance = ep.getInstance();
                LOG.info("lookupWrapper: found " + name + " via EP, instance="
                        + (instance != null ? instance.getClass().getName() : "null"));
                if (instance instanceof com.intellij.codeInspection.LocalInspectionTool local) {
                    return new com.intellij.codeInspection.ex.LocalInspectionToolWrapper(local);
                } else if (instance instanceof com.intellij.codeInspection.GlobalInspectionTool global) {
                    return new com.intellij.codeInspection.ex.GlobalInspectionToolWrapper(global);
                }
            }
        }
        return null;
    }

    @NotNull
    private List<Diagnostic> toDiagnostics(@NotNull List<ProblemDescriptor> descriptors, @NotNull PsiFile psiFile) {
        var doc = FileDocumentManager.getInstance().getDocument(psiFile.getVirtualFile());
        if (doc == null) return List.of();

        var result = new ArrayList<Diagnostic>();
        for (var d : descriptors) {
            var element = d.getPsiElement();
            if (element == null || !element.isValid()) continue;

            var file = element.getContainingFile();
            if (file == null) continue;

            var textRange = element.getTextRange();
            var startLine = doc.getLineNumber(textRange.getStartOffset());
            var startCol = textRange.getStartOffset() - doc.getLineStartOffset(startLine);
            var endLine = doc.getLineNumber(textRange.getEndOffset());
            var endCol = textRange.getEndOffset() - doc.getLineStartOffset(endLine);

            var sevKey = d.getHighlightType().name();
            var severity = typeToSeverity.getOrDefault(sevKey, DiagnosticSeverity.Information);
            var diag = new Diagnostic(
                    new Range(new Position(startLine, startCol), new Position(endLine, endCol)),
                    d.getDescriptionTemplate()
            );
            diag.setSeverity(severity);
            result.add(diag);
        }
        return result;
    }
}
