package tf.locals.idealsp.server.codeactions;

import com.google.gson.GsonBuilder;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.jetbrains.annotations.NotNull;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.diagnostics.DiagnosticsService;
import tf.locals.idealsp.server.util.EditorUtil;
import tf.locals.idealsp.server.util.MiscUtil;
import tf.locals.idealsp.server.util.TextUtil;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service(Service.Level.PROJECT)
public final class CodeActionService {
  private static final Logger LOG = Logger.getInstance(CodeActionService.class);

  private final @NotNull Project project;

  public CodeActionService(@NotNull Project project) {
    this.project = project;
  }

  @NotNull
  private static CodeAction toCodeAction(@NotNull LspPath path,
                                         @NotNull Range range,
                                         @NotNull Object descriptor,
                                          @NotNull String kind) {
    String text;
    final Object desc = descriptor;
    try {
      var method = desc.getClass().getMethod("getAction");
      var action = method.invoke(desc);
      text = (String) action.getClass().getMethod("getText").invoke(action);
    } catch (Exception e) {
      text = desc.toString();
    }
    final String finalText = text;
    return MiscUtil.with(new CodeAction(ReadAction.compute(() -> finalText)), ca -> {
      ca.setKind(kind);
      ca.setData(new ActionData(path.toLspUri(), range));
    });
  }

  @NotNull
  private static <T> Predicate<T> distinctByKey(@NotNull Function<? super T, ?> keyExtractor) {
    Set<Object> seen = new HashSet<>();
    return t -> seen.add(keyExtractor.apply(t));
  }

  @NotNull
  public List<CodeAction> getCodeActions(@NotNull LspPath path, @NotNull Range range) {

    var result = new Ref<List<CodeAction>>();
    ApplicationManager.getApplication().invokeAndWait(
        () -> MiscUtil.invokeWithPsiFileInReadAction(project, path, (file) -> {
          final var disposable = Disposer.newDisposable();

          try {
            EditorUtil.withEditor(disposable, file, range.getStart(), (editor) -> {
              Object actionInfo;
              try {
                var method = ShowIntentionsPass.class.getMethod("getActionsToShow", 
                    com.intellij.openapi.editor.Editor.class, 
                    com.intellij.psi.PsiFile.class, 
                    boolean.class);
                actionInfo = method.invoke(null, editor, file, true);
              } catch (Exception e) {
                try {
                  var method = ShowIntentionsPass.class.getMethod("getActionsToShow", 
                      com.intellij.openapi.editor.Editor.class, 
                      com.intellij.psi.PsiFile.class);
                  actionInfo = method.invoke(null, editor, file);
                } catch (Exception e2) {
                  actionInfo = null;
                }
              }

              if (actionInfo == null) {
                result.set(Collections.emptyList());
                return;
              }

              java.util.Collection<?> errorFixes = Collections.emptyList();
              java.util.Collection<?> inspectionFixes = Collections.emptyList();
              java.util.Collection<?> intentions = Collections.emptyList();
              
              try {
                var errorFixesField = actionInfo.getClass().getField("errorFixesToShow");
                var inspectionFixesField = actionInfo.getClass().getField("inspectionFixesToShow");
                var intentionsField = actionInfo.getClass().getField("intentionsToShow");
                errorFixes = (java.util.Collection<?>) errorFixesField.get(actionInfo);
                inspectionFixes = (java.util.Collection<?>) inspectionFixesField.get(actionInfo);
                intentions = (java.util.Collection<?>) intentionsField.get(actionInfo);
              } catch (Exception ignored) {}

              final var quickFixes = diagnostics().getQuickFixes(path, range).stream()
                  .map(it -> toCodeAction(path, range, it, CodeActionKind.QuickFix));

              final var intentionActions = Stream.of(errorFixes, inspectionFixes, intentions)
                  .flatMap(Collection::stream)
                  .map(it -> toCodeAction(path, range, it, CodeActionKind.Refactor));

              final var actions = Stream.concat(quickFixes, intentionActions)
                  .filter(distinctByKey(CodeAction::getTitle))
                  .collect(Collectors.toList());

              result.set(actions);
            });
          } catch (Exception e) {
            LOG.warn("getCodeActions error: " + e);
            result.set(Collections.emptyList());
          } finally {
            Disposer.dispose(disposable);
          }
        }));

    return Optional.ofNullable(result.get()).orElse(Collections.emptyList());
  }

  @NotNull
  public WorkspaceEdit applyCodeAction(@NotNull CodeAction codeAction) {
    final var actionData = new GsonBuilder().create()
        .fromJson(codeAction.getData().toString(), ActionData.class);

    final var path = LspPath.fromLspUri(actionData.getUri());
    final var result = new WorkspaceEdit();

    var disposable = Disposer.newDisposable();

    try {
      final var psiFile = MiscUtil.resolvePsiFile(project, path);

      if (psiFile == null) {
        LOG.error("couldn't find PSI file: " + path);
        return result;
      }

      final var oldCopy = ((PsiFile) psiFile.copy());

      try {
        ApplicationManager.getApplication().invokeAndWait(() -> {
          final var editor = EditorUtil.createEditor(disposable, psiFile, actionData.getRange().getStart());

          final var quickFixes = diagnostics().getQuickFixes(path, actionData.getRange());
          
          Object actionInfo2;
          try {
            var method = ShowIntentionsPass.class.getMethod("getActionsToShow", 
                com.intellij.openapi.editor.Editor.class, 
                com.intellij.psi.PsiFile.class, 
                boolean.class);
            actionInfo2 = method.invoke(null, editor, psiFile, true);
          } catch (Exception e) {
            try {
              var method = ShowIntentionsPass.class.getMethod("getActionsToShow", 
                  com.intellij.openapi.editor.Editor.class, 
                  com.intellij.psi.PsiFile.class);
              actionInfo2 = method.invoke(null, editor, psiFile);
            } catch (Exception e2) {
              actionInfo2 = null;
            }
          }

          if (actionInfo2 == null) {
            return;
          }

          java.util.Collection<?> errorFixes = Collections.emptyList();
          java.util.Collection<?> inspectionFixes = Collections.emptyList();
          java.util.Collection<?> intentions = Collections.emptyList();
          
          try {
            var errorFixesField = actionInfo2.getClass().getField("errorFixesToShow");
            var inspectionFixesField = actionInfo2.getClass().getField("inspectionFixesToShow");
            var intentionsField = actionInfo2.getClass().getField("intentionsToShow");
            errorFixes = (java.util.Collection<?>) errorFixesField.get(actionInfo2);
            inspectionFixes = (java.util.Collection<?>) inspectionFixesField.get(actionInfo2);
            intentions = (java.util.Collection<?>) intentionsField.get(actionInfo2);
          } catch (Exception ignored) {}

          var actionFound = Stream.of(
                  quickFixes,
                  errorFixes,
                  inspectionFixes,
                  intentions)
              .flatMap(Collection::stream)
              .map(it -> (Object) it)
              .map(obj -> {
                try {
                  return obj.getClass().getMethod("getAction").invoke(obj);
                } catch (Exception e) {
                  return obj;
                }
              })
              .filter(it -> it.toString().contains(codeAction.getTitle()))
              .findFirst()
              .orElse(null);

          if (actionFound == null) {
            LOG.warn("No action descriptor found: " + codeAction.getTitle());
            return;
          }

          try {
            var startInWriteActionMethod = actionFound.getClass().getMethod("startInWriteAction");
            boolean startInWriteAction = (boolean) startInWriteActionMethod.invoke(actionFound);
            
            var invokeMethod = actionFound.getClass().getMethod("invoke", com.intellij.openapi.project.Project.class, com.intellij.openapi.editor.Editor.class, com.intellij.psi.PsiFile.class);

            CommandProcessor.getInstance().executeCommand(project, () -> {
              if (startInWriteAction) {
                WriteAction.run(() -> {
                  try {
                    invokeMethod.invoke(actionFound, project, editor, psiFile);
                  } catch (Exception ex) {
                    LOG.warn("invoke error: " + ex);
                  }
                });
              } else {
                try {
                  invokeMethod.invoke(actionFound, project, editor, psiFile);
                } catch (Exception ex) {
                  LOG.warn("invoke error: " + ex);
                }
              }
            }, codeAction.getTitle(), null);
          } catch (Exception e) {
            LOG.warn("Failed to invoke action: " + e);
          }
        });
      } catch (Exception e) {
        LOG.warn("applyCodeAction error: " + e);
      }

      final var oldDoc = new Ref<Document>();
      final var newDoc = new Ref<Document>();

      ReadAction.run(() -> {
        oldDoc.set(Objects.requireNonNull(MiscUtil.getDocument(oldCopy)));
        newDoc.set(Objects.requireNonNull(MiscUtil.getDocument(psiFile)));
      });

      final var edits = TextUtil.textEditFromDocs(oldDoc.get(), newDoc.get());

      WriteCommandAction.runWriteCommandAction(project, () -> {
        newDoc.get().setText(oldDoc.get().getText());
        PsiDocumentManager.getInstance(project).commitDocument(newDoc.get());
      });

      if (!edits.isEmpty()) {
        diagnostics().haltDiagnostics(path);  // all cached quick fixes are no longer valid
        result.setChanges(Map.of(actionData.getUri(), edits));
      }
    } finally {
      ApplicationManager.getApplication().invokeAndWait(() -> Disposer.dispose(disposable));
    }

    diagnostics().launchDiagnostics(path);
    return result;
  }

  @NotNull
  private DiagnosticsService diagnostics() {
    return project.getService(DiagnosticsService.class);
  }

}
