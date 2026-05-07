package tf.locals.idealsp.server.inspections;

import org.jetbrains.annotations.NotNull;

public class InspectionInfo {

    private String shortName;
    private String displayName;
    private String group;
    private boolean enabled;
    private String description;

    public InspectionInfo() {
    }

    public InspectionInfo(@NotNull String shortName, @NotNull String displayName,
                          @NotNull String group, boolean enabled, @NotNull String description) {
        this.shortName = shortName;
        this.displayName = displayName;
        this.group = group;
        this.enabled = enabled;
        this.description = description;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
