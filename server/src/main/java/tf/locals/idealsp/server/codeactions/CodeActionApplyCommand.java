package tf.locals.idealsp.server.codeactions;

import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.commands.ExecutorContext;
import tf.locals.idealsp.server.commands.LspCommand;
import tf.locals.idealsp.server.diagnostics.DiagnosticsService;
import tf.locals.idealsp.server.util.EditorUtil;
import tf.locals.idealsp.server.util.MiscUtil;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class CodeActionApplyCommand extends LspCommand<ApplyWorkspaceEditResponse> {
    private static final Logger LOG = Logger.getInstance(CodeActionApplyCommand.class);

    private static final Method sIsAppliedMethod;
    private static final Field sAppliedField;
    private static final Field sMessageField;
    private static final Constructor<?> sCtor2;

    static {
        Method m = null;
        Field f1 = null;
        Field f2 = null;
        Constructor<?> c2 = null;
        try {
            m = ApplyWorkspaceEditResponse.class.getMethod("isApplied");
        } catch (Exception ignored) {}
        try {
            f1 = ApplyWorkspaceEditResponse.class.getField("applied");
        } catch (Exception ignored) {}
        try {
            f2 = ApplyWorkspaceEditResponse.class.getDeclaredField("message");
            f2.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            c2 = ApplyWorkspaceEditResponse.class.getConstructor(boolean.class, String.class);
            c2.setAccessible(true);
        } catch (Exception ignored) {}
        sIsAppliedMethod = m;
        sAppliedField = f1;
        sMessageField = f2;
        sCtor2 = c2;
    }

    private final @NotNull String title;
    private final @NotNull LspPath path;
    private final @NotNull Range range;

    public CodeActionApplyCommand(@NotNull String title, @NotNull LspPath path, @NotNull Range range) {
        this.title = title;
        this.path = path;
        this.range = range;
    }

    @Override
    protected @NotNull Supplier<@NotNull String> getMessageSupplier() {
        return () -> "CodeActionApply: " + title + " at " + path;
    }

    @Override
    protected boolean isCancellable() {
        return false;
    }

    private static @NotNull ApplyWorkspaceEditResponse makeResponse(boolean applied, @Nullable String message) {
        try {
            if (sCtor2 != null) {
                return (ApplyWorkspaceEditResponse) sCtor2.newInstance(applied, message);
            }
        } catch (Exception ignored) {}
        try {
            var inst = new ApplyWorkspaceEditResponse(applied);
            if (sMessageField != null) sMessageField.set(inst, message);
            return inst;
        } catch (Exception ignored) {}
        return new ApplyWorkspaceEditResponse(applied);
    }

    private static void tryInitDescriptor(@NotNull Object descriptor,
                                          @NotNull Project project,
                                          @Nullable Editor editor,
                                          @NotNull PsiFile file) {
        try {
            Object action;
            try {
                action = descriptor.getClass().getMethod("getAction").invoke(descriptor);
            } catch (NoSuchMethodException e) {
                action = descriptor;
            }
            action.getClass()
                .getMethod("isAvailable", Project.class, Editor.class, PsiFile.class)
                .invoke(action, project, editor, file);
        } catch (Exception ignored) {}
    }

    private static @NotNull String tryGetText(@NotNull Object action) {
        try {
            return (String) action.getClass().getMethod("getText").invoke(action);
        } catch (Exception e) {
            return action.toString();
        }
    }

    @Override
    protected @Nullable ApplyWorkspaceEditResponse execute(@NotNull ExecutorContext ctx) {
        LOG.warn("CodeActionApplyCommand.execute: title=" + title + " path=" + path);
        var project = ctx.getProject();
        var result = new Ref<ApplyWorkspaceEditResponse>();
        var pathToUse = this.path;

        ApplicationManager.getApplication().invokeAndWait(() -> {
            MiscUtil.invokeWithPsiFileInReadAction(project, pathToUse, (psiFile) -> {
                var disposable = Disposer.newDisposable();
                try {
                    EditorUtil.withEditor(disposable, psiFile, range.getStart(), (editor) -> {
                        var actionFound = findActionByTitle(project, editor, psiFile);
                        if (actionFound == null) {
                            result.set(makeResponse(false, "No action found with title: " + title));
                            return;
                        }

                        invokeAction(project, editor, psiFile, actionFound, title);
                        result.set(makeResponse(true, null));
                    });
                } finally {
                    Disposer.dispose(disposable);
                }
            });
        });

        var response = result.get();
        if (response != null) {
            boolean applied;
            if (sIsAppliedMethod != null) {
                try {
                    applied = (boolean) sIsAppliedMethod.invoke(response);
                } catch (Exception e) {
                    applied = false;
                }
            } else if (sAppliedField != null) {
                try {
                    applied = sAppliedField.getBoolean(response);
                } catch (Exception e) {
                    applied = false;
                }
            } else {
                applied = false;
            }
            if (applied) {
                diagnostics(project).haltDiagnostics(pathToUse);
                diagnostics(project).launchDiagnostics(pathToUse);
            }
        }

        return response != null ? response : makeResponse(false, "Internal error");
    }

    private @Nullable Object findActionByTitle(@NotNull Project project,
                                                @NotNull Editor editor,
                                                @NotNull PsiFile psiFile) {
        Object actionInfo;
        try {
            var method = ShowIntentionsPass.class.getMethod("getActionsToShow",
                com.intellij.openapi.editor.Editor.class,
                com.intellij.psi.PsiFile.class,
                boolean.class);
            actionInfo = method.invoke(null, editor, psiFile, true);
        } catch (Exception e) {
            try {
                var method = ShowIntentionsPass.class.getMethod("getActionsToShow",
                    com.intellij.openapi.editor.Editor.class,
                    com.intellij.psi.PsiFile.class);
                actionInfo = method.invoke(null, editor, psiFile);
            } catch (Exception e2) {
                actionInfo = null;
            }
        }

        if (actionInfo == null) {
            return null;
        }

        var errorFixes = getField(actionInfo, "errorFixesToShow");
        var inspectionFixes = getField(actionInfo, "inspectionFixesToShow");
        var intentions = getField(actionInfo, "intentionsToShow");

        Stream.of(errorFixes, inspectionFixes, intentions)
            .flatMap(Collection::stream)
            .forEach(d -> tryInitDescriptor(d, project, editor, psiFile));

        return Stream.of(errorFixes, inspectionFixes, intentions)
            .flatMap(Collection::stream)
            .map(it -> getAction(it))
            .filter(Objects::nonNull)
            .filter(it -> title.equals(tryGetText(it)))
            .findFirst()
            .orElse(null);
    }

    private void invokeAction(@NotNull Project project,
                              @NotNull Editor editor,
                              @NotNull PsiFile psiFile,
                              @NotNull Object action,
                              @NotNull String commandName) {
        try {
            var startInWriteActionMethod = action.getClass().getMethod("startInWriteAction");
            boolean startInWriteAction = (boolean) startInWriteActionMethod.invoke(action);

            var invokeMethod = action.getClass().getMethod("invoke",
                com.intellij.openapi.project.Project.class,
                com.intellij.openapi.editor.Editor.class,
                com.intellij.psi.PsiFile.class);

            CommandProcessor.getInstance().executeCommand(project, () -> {
                if (startInWriteAction) {
                    WriteAction.run(() -> {
                        try {
                            invokeMethod.invoke(action, project, editor, psiFile);
                        } catch (Exception ex) {
                            LOG.warn("invoke error: " + ex);
                        }
                    });
                } else {
                    try {
                        invokeMethod.invoke(action, project, editor, psiFile);
                    } catch (Exception ex) {
                        LOG.warn("invoke error: " + ex);
                    }
                }
            }, commandName, null);
        } catch (Exception e) {
            LOG.warn("Failed to invoke action: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private static @NotNull Collection<?> getField(@NotNull Object obj, @NotNull String fieldName) {
        try {
            var field = obj.getClass().getField(fieldName);
            return (Collection<?>) field.get(obj);
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    private static @Nullable Object getAction(@NotNull Object descriptor) {
        try {
            return descriptor.getClass().getMethod("getAction").invoke(descriptor);
        } catch (Exception e) {
            return descriptor;
        }
    }

    private @NotNull DiagnosticsService diagnostics(@NotNull Project project) {
        return project.getService(DiagnosticsService.class);
    }
}