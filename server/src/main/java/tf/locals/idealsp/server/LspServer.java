package tf.locals.idealsp.server;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManagerListener;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.util.messages.MessageBusConnection;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tf.locals.idealsp.server.dataflow.DataFlowFromCommand;
import tf.locals.idealsp.server.dataflow.DataFlowLocation;
import tf.locals.idealsp.server.dataflow.DataFlowParams;
import tf.locals.idealsp.server.dataflow.DataFlowToCommand;
import tf.locals.idealsp.server.inspections.InspectionInfo;
import tf.locals.idealsp.server.inspections.InspectionListParams;
import tf.locals.idealsp.server.inspections.InspectionRunByNameParams;
import tf.locals.idealsp.server.inspections.InspectionService;
import tf.locals.idealsp.server.codeactions.CodeActionApplyCommand;
import tf.locals.idealsp.server.codeactions.CodeActionApplyParams;
import tf.locals.idealsp.server.util.Metrics;
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class LspServer implements IdeaLspServer, LanguageClientAware, LspSession, DumbService.DumbModeListener {
  private final static Logger LOG = Logger.getInstance(LspServer.class);
  private final MyTextDocumentService myTextDocumentService = new MyTextDocumentService(this);
  private final MyWorkspaceService myWorkspaceService = new MyWorkspaceService(this);

  @NotNull
  private final MessageBusConnection messageBusConnection;
  @Nullable
  private MyLanguageClient client = null;

  @Nullable
  private Project project = null;

  @Nullable
  private volatile LspPath workspaceRoot = null;

  private volatile boolean stopped = false;

  public LspServer() {
    messageBusConnection = ApplicationManager.getApplication().getMessageBus().connect();
    messageBusConnection.subscribe(ProgressManagerListener.TOPIC, new WorkDoneProgressReporter());
  }

  @NotNull
  @Override
  public CompletableFuture<InitializeResult> initialize(@NotNull InitializeParams params) {
    return CompletableFuture.supplyAsync(() -> {
      final var workspaceFolders = params.getWorkspaceFolders();

      if (workspaceFolders == null) {
        return new InitializeResult(new ServerCapabilities());
      }

      //   // todo how about multiple folders
      final var projectRoot = LspPath.fromLspUri(workspaceFolders.get(0).getUri());

      if (workspaceRoot != null) {
        ProjectSessionRegistry.getInstance().releaseProject(workspaceRoot);
      }
      workspaceRoot = projectRoot;

      Metrics.run(() -> "initialize: " + projectRoot, () -> {

        LOG.info("Opening project: " + projectRoot);
        project = ProjectSessionRegistry.getInstance().openOrClaimProject(projectRoot);

        assert client != null;
        LspContext.createContext(project, client, params.getCapabilities());
        project.getMessageBus().connect().subscribe(DumbService.DUMB_MODE, this);

        LOG.info("LSP was initialized. Project: " + project);
      });

      var capabilities = defaultServerCapabilities();
      capabilities.setExperimental(buildStatus(project));
      return new InitializeResult(capabilities);
    });
  }

  @NotNull
  private Map<String, Object> buildStatus(@Nullable Project p) {
    var status = new HashMap<String, Object>();
    status.put("server", "idealsp");
    if (p == null || p.isDisposed()) {
      status.put("ready", false);
      return status;
    }
    try {
      status.put("ready", true);
      status.put("projectOpen", p.isOpen());
      status.put("projectInitialized", p.isInitialized());
      status.put("dumbMode", DumbService.isDumb(p));
      var mods = ModuleManager.getInstance(p).getModules();
      status.put("moduleCount", mods.length);
      status.put("contentRootCount",
          Arrays.stream(mods)
              .mapToInt(m -> ModuleRootManager.getInstance(m).getContentRoots().length)
              .sum());
      status.put("workspaceRoot", workspaceRoot != null ? workspaceRoot.toString() : null);
    } catch (Exception e) {
      LOG.warn("Failed to build status for initialize response", e);
      status.put("statusError", e.getMessage());
    }
    return status;
  }

  @NotNull
  private CompletionOptions defaultCompletionOptions() {
    var completionOptions = new CompletionOptions(true, List.of(".", "@"));
    completionOptions.setResolveProvider(true);
    var completionItemOptions = new CompletionItemOptions();
    completionItemOptions.setLabelDetailsSupport(true);
    completionOptions.setCompletionItem(completionItemOptions);
    return completionOptions;
  }

  @NotNull
  private ServerCapabilities defaultServerCapabilities() {

    return MiscUtil.with(new ServerCapabilities(), it -> {
      it.setTextDocumentSync(MiscUtil.with(new TextDocumentSyncOptions(), (syncOptions) -> {
        syncOptions.setOpenClose(true);
        syncOptions.setChange(TextDocumentSyncKind.Incremental);
        syncOptions.setSave(new SaveOptions(true));
      }));

      it.setWorkspace(MiscUtil.with(new WorkspaceServerCapabilities(), wsc ->
          wsc.setFileOperations(MiscUtil.with(
              new FileOperationsServerCapabilities(),
              foc -> foc.setDidRename(new FileOperationOptions(
                  List.of(new FileOperationFilter(new FileOperationPattern("**/*"), "file"))
              ))
          ))
      ));

it.setHoverProvider(true);
      it.setCompletionProvider(defaultCompletionOptions());
      it.setSignatureHelpProvider(MiscUtil.with(new SignatureHelpOptions(), signatureHelpOptions -> signatureHelpOptions.setTriggerCharacters(List.of("(", "["))));
      it.setDefinitionProvider(true);
      it.setTypeDefinitionProvider(true);
      it.setImplementationProvider(true);
      it.setReferencesProvider(true);
      it.setDocumentHighlightProvider(true);
      it.setDocumentSymbolProvider(true);
      it.setWorkspaceSymbolProvider(true);
//      it.setCodeLensProvider(new CodeLensOptions(false));
      it.setDocumentFormattingProvider(true);
      it.setDocumentRangeFormattingProvider(true);
      it.setDocumentOnTypeFormattingProvider(defaultOnTypeFormattingOptions());

      it.setRenameProvider(true);
//      it.setDocumentLinkProvider(null);
//      it.setExecuteCommandProvider(new ExecuteCommandOptions());

       it.setCodeActionProvider(
           MiscUtil.with(
               new CodeActionOptions(List.of(CodeActionKind.QuickFix)),
               cao -> cao.setResolveProvider(true)
           )
       );

it.setCallHierarchyProvider(true);

       it.setExperimental(null);

     });
  }

  @NotNull
  private static DocumentOnTypeFormattingOptions defaultOnTypeFormattingOptions() {
    return new DocumentOnTypeFormattingOptions(";",
        List.of( // "{", "(", "<",  "\"", "'", "[", todo decide how to handle this cases
            "}", ")", "]", ">", ":", ",", ".", "@", "#", "?", "=", "!", " ",
            "|", "&", "$", "^", "%", "*", "/")
    );
  }

  public CompletableFuture<Object> shutdown() {
    return CompletableFuture.supplyAsync(() -> {
      stop();
      return null;
    });
  }

  public void exit() {
    stop();
  }

  public synchronized void stop() {
    if (stopped) return;
    stopped = true;
    messageBusConnection.disconnect();

    if (workspaceRoot != null) {
      ProjectSessionRegistry.getInstance().releaseProject(workspaceRoot);
      workspaceRoot = null;
    }
    this.project = null;
  }

  @Override
  public MyTextDocumentService getTextDocumentService() {
    return myTextDocumentService;
  }

  @Override
  public MyWorkspaceService getWorkspaceService() {
    return myWorkspaceService;
  }

  @Override
  public void connect(@NotNull LanguageClient client) {
    assert client instanceof MyLanguageClient;
    this.client = (MyLanguageClient) client;
  }

  @NotNull
  private MyLanguageClient getClient() {
    assert client != null;
    return client;
  }

  @NotNull
  @Override
  public Project getProject() {
    if (project == null)
      throw new IllegalStateException("LSP session is not yet initialized");
    return project;
  }

  @Override
  public void enteredDumbMode() {
    LOG.info("Entered dumb mode. Notifying client...");
    getClient().notifyIndexStarted();
  }

  @Override
  public void exitDumbMode() {
    LOG.info("Exited dumb mode. Refreshing diagnostics...");
    getClient().notifyIndexFinished();
    getTextDocumentService().refreshDiagnostics();
  }

  public CompletableFuture<List<DataFlowLocation>> dataFlowFrom(@NotNull DataFlowParams params) {
    try {
      var project = getProject();
      return new DataFlowFromCommand(params.getPosition())
          .runAsync(project, LspPath.fromLspUri(params.getTextDocument().getUri()));
    } catch (Exception e) {
      LOG.error("dataFlowFrom() failed", e);
      return MiscUtil.failed("dataflowFrom", e.getMessage());
    }
  }

  public CompletableFuture<List<DataFlowLocation>> dataFlowTo(@NotNull DataFlowParams params) {
    try {
      var project = getProject();
      return new DataFlowToCommand(params.getPosition())
          .runAsync(project, LspPath.fromLspUri(params.getTextDocument().getUri()));
    } catch (Exception e) {
      LOG.error("dataFlowTo() failed", e);
      return MiscUtil.failed("dataflowTo", e.getMessage());
    }
  }

  public CompletableFuture<org.eclipse.lsp4j.ApplyWorkspaceEditResponse> codeActionApply(
          @NotNull CodeActionApplyParams params) {
    try {
      var project = getProject();
      return new CodeActionApplyCommand(
              params.getTitle(),
              LspPath.fromLspUri(params.getUri()),
              params.getRange()
      ).runAsync(project, LspPath.fromLspUri(params.getUri()));
    } catch (Exception e) {
      LOG.error("codeActionApply() failed", e);
      return MiscUtil.failed("codeActionApply", e.getMessage());
    }
  }

  @Override
  public CompletableFuture<List<InspectionInfo>> inspectionList(@NotNull InspectionListParams params) {
    try {
      if (project == null) {
        LOG.warn("inspectionList() called but project is not yet initialized");
        return CompletableFuture.completedFuture(List.of());
      }
      return CompletableFuture.supplyAsync(() ->
          project.getService(InspectionService.class).listInspections(params.getQuery()));
    } catch (Exception e) {
      LOG.error("inspectionList() failed", e);
      return MiscUtil.failed("inspectionList", e.getMessage());
    }
  }

  @Override
  public CompletableFuture<List<org.eclipse.lsp4j.Diagnostic>> inspectionRunByName(@NotNull InspectionRunByNameParams params) {
    try {
      if (project == null) {
        LOG.warn("inspectionRunByName() called but project is not yet initialized");
        return CompletableFuture.completedFuture(List.of());
      }
      var textDoc = params.getTextDocument();
      if (textDoc == null || textDoc.getUri() == null || textDoc.getUri().isEmpty()) {
        return CompletableFuture.supplyAsync(() -> {
          return ApplicationManager.getApplication().runReadAction(
              (com.intellij.openapi.util.Computable<List<org.eclipse.lsp4j.Diagnostic>>) () -> {
                return project.getService(InspectionService.class).runByNameOnAllFiles(params.getName());
              });
        });
      }
      var path = LspPath.fromLspUri(textDoc.getUri());
      return CompletableFuture.supplyAsync(() -> {
        return ApplicationManager.getApplication().runReadAction(
            (com.intellij.openapi.util.Computable<List<org.eclipse.lsp4j.Diagnostic>>) () -> {
              var psiFile = com.intellij.psi.PsiManager.getInstance(project)
                      .findFile(path.findVirtualFile());
              if (psiFile == null) {
                LOG.warn("inspectionRunByName: file not found: " + path);
                return List.<org.eclipse.lsp4j.Diagnostic>of();
              }
              return project.getService(InspectionService.class).runByName(psiFile, params.getName());
            });
      });
    } catch (Exception e) {
      LOG.error("inspectionRunByName() failed", e);
      return MiscUtil.failed("inspectionRunByName", e.getMessage());
    }
  }

  private class WorkDoneProgressReporter implements ProgressManagerListener {
    @Override
    public void afterTaskStart(@NotNull Task task, @NotNull ProgressIndicator indicator) {
      if(task.getProject() == null || !task.getProject().equals(project))
        return;

      var client = LspServer.this.client;

      if(client == null)
        return;

      final String token = calculateUniqueToken(task);
      try {
        client
            .createProgress(new WorkDoneProgressCreateParams(Either.forLeft(token)))
            .get(500, TimeUnit.MILLISECONDS);
      } catch (InterruptedException | ExecutionException | TimeoutException e) {
        LOG.warn("Could not get confirmation when creating work done progress; will act as if it's created", e);
      }

      final var progressBegin = new WorkDoneProgressBegin();
      progressBegin.setTitle(task.getTitle());
      progressBegin.setCancellable(false);
      progressBegin.setPercentage(0);
      client.notifyProgress(new ProgressParams(Either.forLeft(token), Either.forLeft(progressBegin)));
    }

    @Override
    public void afterTaskFinished(@NotNull Task task) {
      if(task.getProject() != null && !task.getProject().equals(project))
        return;

      var client = LspServer.this.client;

      if(client == null)
        return;

      final String token = calculateUniqueToken(task);
      client.notifyProgress(new ProgressParams(Either.forLeft(token), Either.forLeft(new WorkDoneProgressEnd())));
    }

    private String calculateUniqueToken(@NotNull Task task) {
      return task.getClass().getName() + '@' + System.identityHashCode(task);
    }
  }
}