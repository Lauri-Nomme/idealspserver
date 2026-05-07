package tf.locals.idealsp.server.inspections;

import com.intellij.codeInspection.InspectionEP;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Service(Service.Level.PROJECT)
final public class InspectionService {

    private static final Logger LOG = Logger.getInstance(InspectionService.class);

    @NotNull
    private final Project project;

    public InspectionService(@NotNull Project project) {
        this.project = project;
    }

    @NotNull
    public List<InspectionInfo> listInspections(@NotNull String query) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<InspectionInfo>>) () -> {
            @SuppressWarnings("removal")
            var profile = InspectionProjectProfileManager.getInstance(project).getInspectionProfile();
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
}
