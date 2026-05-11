package tf.locals.idealsp.server;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.jetbrains.annotations.NotNull;
import tf.locals.idealsp.server.callhierarchy.*;
import tf.locals.idealsp.server.codeactions.CodeActionService;
import tf.locals.idealsp.server.completions.CompletionService;
import tf.locals.idealsp.server.diagnostics.DiagnosticsService;
import tf.locals.idealsp.server.formatting.FormattingCommand;
import tf.locals.idealsp.server.formatting.OnTypeFormattingCommand;
import tf.locals.idealsp.server.references.*;
import tf.locals.idealsp.server.hover.HoverCommand;
import tf.locals.idealsp.server.rename.RenameCommand;
import tf.locals.idealsp.server.signature.SignatureHelpService;
import tf.locals.idealsp.server.symbol.DocumentSymbolService;
import tf.locals.idealsp.server.util.Metrics;
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

public class MyTextDocumentService implements TextDocumentService {

  private static final Logger LOG = Logger.getInstance(MyTextDocumentService.class);
  private final @NotNull LspSession session;

  public MyTextDocumentService(@NotNull LspSession session) {
    this.session = session;
  }
  @Override
  public void didOpen(DidOpenTextDocumentParams params) {
    final var textDocument = params.getTextDocument();

    final var path = LspPath.fromLspUri(textDocument.getUri());

    Metrics.run(() -> "didOpen: " + path, () -> {
      documents().startManaging(textDocument);
      diagnostics().launchDiagnostics(path);

      if (DumbService.isDumb(session.getProject())) {
        LOG.debug("Sending indexing started: " + path);
        LspContext.getContext(session.getProject()).getClient().notifyIndexStarted();
      }
  /*  todo
        val projectSdk = ProjectRootManager.getInstance(project).projectSdk
        if (projectSdk == null) {
          warnNoJdk(client)
        }
*/
    });
  }

  @Override
  public void didChange(DidChangeTextDocumentParams params) {
    final var path = LspPath.fromLspUri(params.getTextDocument().getUri());

    Metrics.run(() -> "didChange: " + path, () -> {
      documents().updateDocument(params);
      diagnostics().launchDiagnostics(path);
    });
  }

  @Override
  public void didClose(DidCloseTextDocumentParams params) {
    diagnostics().haltDiagnostics(LspPath.fromLspUri(params.getTextDocument().getUri()));
    documents().stopManaging(params.getTextDocument());
  }

  @Override
  public void didSave(DidSaveTextDocumentParams params) {
    documents().syncDocument(params.getTextDocument());
    diagnostics().launchDiagnostics(LspPath.fromLspUri(params.getTextDocument().getUri()));
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
    try {
      var project = session.getProject();
      if (project == null) {
        LOG.warn("definition() called but project is not yet initialized");
        return CompletableFuture.completedFuture(Either.forRight(List.of()));
      }
      return new FindDefinitionCommand(params.getPosition())
          .runAsync(project, LspPath.fromLspUri(params.getTextDocument().getUri()));
    } catch (Exception e) {
      LOG.error("definition() failed", e);
      return MiscUtil.failed("definition", e.getMessage());
    }
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(TypeDefinitionParams params) {
    return new FindTypeDefinitionCommand(params.getPosition())
        .runAsync(session.getProject(), LspPath.fromLspUri(params.getTextDocument().getUri()));
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(ImplementationParams params) {
    return new FindImplementationCommand(params.getPosition())
        .runAsync(session.getProject(), LspPath.fromLspUri(params.getTextDocument().getUri()));
  }

  @Override
  public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
    try {
      var project = session.getProject();
      if (project == null) {
        LOG.warn("references() called but project is not yet initialized");
        return CompletableFuture.completedFuture(List.of());
      }
      return new FindUsagesCommand(params.getPosition())
          .runAsync(project, LspPath.fromLspUri(params.getTextDocument().getUri()));
    } catch (Exception e) {
      LOG.error("references() failed", e);
      return MiscUtil.failed("references", e.getMessage());
    }
  }

  @Override
  public CompletableFuture<Hover> hover(HoverParams params) {
    return new HoverCommand(params.getPosition())
        .runAsync(session.getProject(), LspPath.fromLspUri(params.getTextDocument().getUri()));
  }

  @Override
  public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) {
    return new DocumentHighlightCommand(params.getPosition())
        .runAsync(session.getProject(), LspPath.fromLspUri(params.getTextDocument().getUri()));
  }

  @SuppressWarnings("deprecation")
  @Override
  public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
    final var path = LspPath.fromLspUri(params.getTextDocument().getUri());
    return CompletableFutures.computeAsync(
        AppExecutorUtil.getAppExecutorService(),
        (cancelChecker) ->
            documentSymbols().computeDocumentSymbols(path, cancelChecker)
    );
  }

  @Override
  public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
    return codeActions().getCodeActionsAsync(
        LspPath.fromLspUri(params.getTextDocument().getUri()),
        params.getRange()
    ).thenApply(actions -> actions.stream()
        .map((Function<CodeAction, Either<Command, CodeAction>>) Either::forRight)
        .collect(Collectors.toList()));
  }


  @Override
  public CompletableFuture<CodeAction> resolveCodeAction(CodeAction unresolved) {
    return CompletableFuture.supplyAsync(() -> {
      var edit = codeActions().applyCodeAction(unresolved);
      unresolved.setEdit(edit);
      return unresolved;
    });
  }

  public void refreshDiagnostics() {
    LOG.info("Start refreshing diagnostics for all opened documents");
    documents().forEach(diagnostics()::launchDiagnostics);
  }

  @NotNull
  private ManagedDocuments documents() {
    return session.getProject().getService(ManagedDocuments.class);
  }

  @NotNull
  private DiagnosticsService diagnostics() {
    return session.getProject().getService(DiagnosticsService.class);
  }

  @NotNull
  private CodeActionService codeActions() {
    return session.getProject().getService(CodeActionService.class);
  }

  @NotNull
  private CompletionService completions() {
    return session.getProject().getService(CompletionService.class);
  }

  @NotNull
  private DocumentSymbolService documentSymbols() {
    return session.getProject().getService(DocumentSymbolService.class);
  }

  @NotNull
  private SignatureHelpService signature() {
    return session.getProject().getService(SignatureHelpService.class);
  }

  @Override
  @NotNull
  public CompletableFuture<CompletionItem> resolveCompletionItem(@NotNull CompletionItem unresolved) {
    return CompletableFutures.computeAsync(
        AppExecutorUtil.getAppExecutorService(),
        (cancelChecker) ->
            completions().resolveCompletion(unresolved, cancelChecker)
    );
  }

  @Override
  @NotNull
  public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(@NotNull CompletionParams params) {
    final var path = LspPath.fromLspUri(params.getTextDocument().getUri());
    return CompletableFutures.computeAsync(
        AppExecutorUtil.getAppExecutorService(),
        (cancelChecker) ->
            Either.forLeft(completions().computeCompletions(path, params.getPosition(), cancelChecker))
    );
  }

  @Override
  @NotNull
  public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
    final var path = LspPath.fromLspUri(params.getTextDocument().getUri());
    return CompletableFutures.computeAsync(AppExecutorUtil.getAppExecutorService(),
        cancelChecker -> signature().computeSignatureHelp(path, params.getPosition(), cancelChecker));
  }


  @Override
  public CompletableFuture<List<? extends TextEdit>> formatting(@NotNull DocumentFormattingParams params) {
    return new FormattingCommand(null, params.getOptions())
        .runAsync(session.getProject(), LspPath.fromLspUri(params.getTextDocument().getUri()));
  }

  @Override
  public CompletableFuture<List<? extends TextEdit>> rangeFormatting(@NotNull DocumentRangeFormattingParams params) {
    return new FormattingCommand(params.getRange(), params.getOptions())
        .runAsync(session.getProject(), LspPath.fromLspUri(params.getTextDocument().getUri()));
  }

    @Override
    public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
        return new OnTypeFormattingCommand(params.getPosition(), params.getOptions(),
                params.getCh().charAt(0)).runAsync(
                session.getProject(), LspPath.fromLspUri(params.getTextDocument().getUri())
        );
    }

    @Override
    public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(@NotNull CallHierarchyPrepareParams params) {
        try {
            var project = session.getProject();
            if (project == null) {
                LOG.warn("prepareCallHierarchy() called but project is not yet initialized");
                return CompletableFuture.completedFuture(List.of());
            }
            return new PrepareCallHierarchyCommand(params.getPosition())
                    .runAsync(project, LspPath.fromLspUri(params.getTextDocument().getUri()));
        } catch (Exception e) {
            LOG.error("prepareCallHierarchy() failed", e);
            return MiscUtil.failed("prepareCallHierarchy", e.getMessage());
        }
    }

    @Override
    public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(@NotNull CallHierarchyIncomingCallsParams params) {
        try {
            var project = session.getProject();
            if (project == null) {
                LOG.warn("callHierarchyIncomingCalls() called but project is not yet initialized");
                return CompletableFuture.completedFuture(List.of());
            }
            return CompletableFuture.supplyAsync(() -> {
                return com.intellij.openapi.application.ApplicationManager.getApplication()
                    .runReadAction((com.intellij.openapi.util.Computable<List<CallHierarchyIncomingCall>>) () -> 
                        new IncomingCallsCommand(params.getItem()).execute(project));
            });
        } catch (Exception e) {
            LOG.error("callHierarchyIncomingCalls() failed", e);
            return MiscUtil.failed("callHierarchyIncomingCalls", e.getMessage());
        }
    }

    @Override
    public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(@NotNull CallHierarchyOutgoingCallsParams params) {
        try {
            var project = session.getProject();
            if (project == null) {
                LOG.warn("callHierarchyOutgoingCalls() called but project is not yet initialized");
                return CompletableFuture.completedFuture(List.of());
            }
            return CompletableFuture.supplyAsync(() -> {
                return com.intellij.openapi.application.ApplicationManager.getApplication()
                    .runReadAction((com.intellij.openapi.util.Computable<List<CallHierarchyOutgoingCall>>) () -> 
                        new OutgoingCallsCommand(params.getItem()).execute(project));
            });
        } catch (Exception e) {
            LOG.error("callHierarchyOutgoingCalls() failed", e);
            return MiscUtil.failed("callHierarchyOutgoingCalls", e.getMessage());
        }
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(@NotNull RenameParams params) {
        try {
            var project = session.getProject();
            if (project == null) {
                LOG.warn("rename() called but project is not yet initialized");
                return CompletableFuture.completedFuture(new WorkspaceEdit());
            }
            return new RenameCommand(params.getPosition(), params.getNewName())
                .runAsync(project, LspPath.fromLspUri(params.getTextDocument().getUri()));
        } catch (Exception e) {
            LOG.error("rename() failed", e);
            return MiscUtil.failed("rename", e.getMessage());
        }
    }
}
