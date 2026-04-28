package tf.locals.idealsp.server.dataflow;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentPositionParams;

public class DataFlowParams extends TextDocumentPositionParams {

    public DataFlowParams() {
    }

    public DataFlowParams(String uri, Position position) {
        super.setTextDocument(new TextDocumentIdentifier(uri));
        super.setPosition(position);
    }
}