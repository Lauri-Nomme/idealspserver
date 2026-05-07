package tf.locals.idealsp.server.inspections;

import org.eclipse.lsp4j.jsonrpc.validation.NonNull;

public class InspectionListParams {

    @NonNull
    private String query = "";

    public InspectionListParams() {
    }

    public InspectionListParams(@NonNull String query) {
        this.query = query;
    }

    @NonNull
    public String getQuery() {
        return query;
    }

    public void setQuery(@NonNull String query) {
        this.query = query;
    }
}
