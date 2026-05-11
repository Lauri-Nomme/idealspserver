package tf.locals.idealsp.server.codeactions;

import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;

public class CodeActionApplyParams {

    private @NotNull String title;
    private @NotNull String uri;
    private @NotNull Range range;

    public CodeActionApplyParams() {
    }

    public CodeActionApplyParams(@NotNull String title, @NotNull String uri, @NotNull Range range) {
        this.title = title;
        this.uri = uri;
        this.range = range;
    }

    public @NotNull String getTitle() {
        return title;
    }

    public void setTitle(@NotNull String title) {
        this.title = title;
    }

    public @NotNull String getUri() {
        return uri;
    }

    public void setUri(@NotNull String uri) {
        this.uri = uri;
    }

    public @NotNull Range getRange() {
        return range;
    }

    public void setRange(@NotNull Range range) {
        this.range = range;
    }
}
