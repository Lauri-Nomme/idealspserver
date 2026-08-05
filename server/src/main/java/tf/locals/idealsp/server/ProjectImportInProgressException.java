package tf.locals.idealsp.server;

import com.intellij.openapi.project.Project;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when an LSP query arrives while the project is still importing/syncing its
 * external build system (Gradle/Maven) or while a write action is stuck. IntelliJ 2026.1
 * runs the workspace-model update inside a write action while holding a read permit;
 * concurrent LSP reads then starve on the writer-preferring lock and would otherwise hang
 * until the client times out. Instead of blocking, we reject the request with a clear
 * error so the client can retry.
 *
 * <p>Extends {@link ResponseErrorException} so the LSP4J layer sends our message verbatim
 * as the JSON-RPC error {@code message} (code -32603), instead of the generic
 * "Internal error." plus a stack trace.
 */
public class ProjectImportInProgressException extends ResponseErrorException {
  public ProjectImportInProgressException(@NotNull Project project) {
    super(new ResponseError(ResponseErrorCode.InternalError,
        "Project is still importing/syncing its build system (Gradle/Maven) or a write "
            + "action is pending: "
            + (project.getBasePath() != null ? project.getBasePath() : project.getName())
            + ". Request rejected instead of waiting on the read/write lock; retry once the import finishes.",
        null));
  }
}
