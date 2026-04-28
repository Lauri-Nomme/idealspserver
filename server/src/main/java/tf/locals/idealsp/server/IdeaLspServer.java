package tf.locals.idealsp.server;

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;
import tf.locals.idealsp.server.dataflow.DataFlowLocation;
import tf.locals.idealsp.server.dataflow.DataFlowParams;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface IdeaLspServer extends LanguageServer {
    
    @JsonRequest("textDocument/dataflowFrom")
    CompletableFuture<List<DataFlowLocation>> dataFlowFrom(DataFlowParams params);
    
    @JsonRequest("textDocument/dataflowTo")
    CompletableFuture<List<DataFlowLocation>> dataFlowTo(DataFlowParams params);
}