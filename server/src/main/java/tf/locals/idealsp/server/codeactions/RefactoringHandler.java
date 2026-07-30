package tf.locals.idealsp.server.codeactions;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class RefactoringHandler {
  private static final Logger LOG = Logger.getInstance(RefactoringHandler.class);

  private RefactoringHandler() {}

  public static boolean isExtractMethodAvailable(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    try {
      Class<?> handlerClass = Class.forName("com.intellij.refactoring.extractMethod.ExtractMethodHandler");
      Method getElements = handlerClass.getMethod("getElements", Project.class, Editor.class, PsiFile.class);
      Object[] elements = (Object[]) getElements.invoke(null, project, editor, file);
      return elements != null && elements.length > 0;
    } catch (Exception e) {
      LOG.warn("isExtractMethodAvailable error: " + e);
      return false;
    }
  }

  public static boolean applyExtractMethod(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    return applyExtractMethod(project, editor, file, null);
  }

  public static boolean applyExtractMethod(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file,
                                            @Nullable String methodName) {
    try {
      // Ensure the editor has a valid selection
      var selectionModel = editor.getSelectionModel();
      com.intellij.openapi.util.TextRange range;
      if (selectionModel.hasSelection()) {
        range = new com.intellij.openapi.util.TextRange(
            selectionModel.getSelectionStart(), selectionModel.getSelectionEnd());
      } else {
        Class<?> handlerClass = Class.forName("com.intellij.refactoring.extractMethod.ExtractMethodHandler");
        Method getElements = handlerClass.getMethod("getElements", Project.class, Editor.class, PsiFile.class);
        PsiElement[] elements = (PsiElement[]) getElements.invoke(null, project, editor, file);
        if (elements == null || elements.length == 0) { LOG.warn("applyExtractMethod: no elements"); return false; }
        int start = Integer.MAX_VALUE, end = -1;
        for (PsiElement el : elements) {
          start = Math.min(start, el.getTextRange().getStartOffset());
          end = Math.max(end, el.getTextRange().getEndOffset());
        }
        if (start >= end) { LOG.warn("applyExtractMethod: invalid range"); return false; }
        selectionModel.setSelection(start, end);
        range = new com.intellij.openapi.util.TextRange(start, end);
      }

      // Use ExtractMethodProcessor directly — synchronous, no coroutines, no dialogs
      Class<?> handlerClass = Class.forName("com.intellij.refactoring.extractMethod.ExtractMethodHandler");
      Class<?> processorClass = Class.forName("com.intellij.refactoring.extractMethod.ExtractMethodProcessor");
      Method getElements = handlerClass.getMethod("getElements", Project.class, Editor.class, PsiFile.class);
      PsiElement[] elements = (PsiElement[]) getElements.invoke(null, project, editor, file);
      if (elements == null || elements.length == 0) { LOG.warn("applyExtractMethod: no elements after selection"); return false; }

      // Call the private 6-param getProcessor with our editor — it creates the processor,
      // validates elements, and calls prepare(null) to skip the dialog
      Method getProcessorPriv = handlerClass.getDeclaredMethod("getProcessor",
          PsiElement[].class, Project.class, PsiFile.class, Editor.class,
          boolean.class, java.util.function.Consumer.class);
      getProcessorPriv.setAccessible(true);
      Object processor = getProcessorPriv.invoke(null, elements, project, file, editor, false, null);
      if (processor == null) { LOG.warn("applyExtractMethod: getProcessor returned null"); return false; }

      // prepareVariablesAndName() sets myVariableDatum which generateEmptyMethod needs.
      // Must be called BEFORE setting the method name because it may override it.
      Method prepVars = processorClass.getMethod("prepareVariablesAndName");
      prepVars.invoke(processor);

      // Set the method name — override the suggestion from prepareVariablesAndName
      var nameField = processorClass.getDeclaredField("myMethodName");
      nameField.setAccessible(true);
      nameField.set(processor, methodName != null && !methodName.isEmpty() ? methodName : "newMethod");

      // Call extractMethod synchronously — this runs inside a CommandProcessor on the EDT
      Method extractMethod = handlerClass.getMethod("extractMethod", Project.class, processorClass);
      extractMethod.invoke(null, project, processor);
      return true;
    } catch (Exception e) {
      Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
      LOG.warn("applyExtractMethod error: " + cause, cause);
      return false;
    }
  }

  private static void renameMethodByName(PsiFile file, String oldName, String newName) {
    com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(file, com.intellij.psi.PsiMethod.class)
        .stream()
        .filter(m -> oldName.equals(m.getName()))
        .findFirst()
        .ifPresent(method -> {
          PsiElement identifier = method.getNameIdentifier();
          if (identifier != null) {
            identifier.replace(
                com.intellij.psi.JavaPsiFacade.getElementFactory(method.getProject()).createIdentifier(newName));
          }
        });
  }

  public static boolean isIntroduceVariableAvailable(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    try {
      Class<?> baseClass = Class.forName("com.intellij.refactoring.introduceVariable.IntroduceVariableBase");
      Method getExpressions = baseClass.getMethod("getExpressionsAndSelectionRange", Project.class, Editor.class, PsiFile.class, int.class);
      int offset = editor.getCaretModel().getOffset();
      Object pair = getExpressions.invoke(null, project, editor, file, offset);
      if (pair == null) return false;
      var secondField = pair.getClass().getMethod("getSecond");
      Object expressions = secondField.invoke(pair);
      return expressions instanceof java.util.List && !((java.util.List<?>) expressions).isEmpty();
    } catch (Exception e) {
      LOG.warn("isIntroduceVariableAvailable error: " + e);
      return false;
    }
  }

  public static boolean applyIntroduceVariable(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    try {
      Class<?> baseClass = Class.forName("com.intellij.refactoring.introduceVariable.IntroduceVariableBase");
      Method getExpressions = baseClass.getMethod("getExpressionsAndSelectionRange", Project.class, Editor.class, PsiFile.class, int.class);
      int offset = editor.getCaretModel().getOffset();
      Object pair = getExpressions.invoke(null, project, editor, file, offset);
      if (pair == null) return false;

      var secondField = pair.getClass().getMethod("getSecond");
      Object expressions = secondField.invoke(pair);
      if (!(expressions instanceof java.util.List) || ((java.util.List<?>) expressions).isEmpty()) return false;

      PsiExpression expression = (PsiExpression) ((java.util.List<?>) expressions).get(0);
      if (expression == null) return false;

      Method getAnchor = baseClass.getMethod("getAnchor", PsiElement.class);
      PsiElement anchor = (PsiElement) getAnchor.invoke(null, expression);

      Class<?> settingsClass = Class.forName("com.intellij.refactoring.introduceVariable.IntroduceVariableSettings");
      PsiType expressionType = expression.getType();
      // Dynamic proxy implementing IntroduceVariableSettings
      Object settings = Proxy.newProxyInstance(settingsClass.getClassLoader(), new Class<?>[]{settingsClass},
          (proxy, method, args) -> {
            String name = method.getName();
            if ("getEnteredName".equals(name)) return "newVar";
            if ("isReplaceAllOccurrences".equals(name)) return true;
            if ("isDeclareFinal".equals(name)) return false;
            if ("isDeclareVarType".equals(name)) return false;
            if ("isReplaceLValues".equals(name)) return true;
            if ("getSelectedType".equals(name)) return expressionType;
            if ("isOK".equals(name)) return true;
            if ("getReplaceChoice".equals(name)) return null;
            return method.getDefaultValue();
          });

      // Call VariableExtractor.introduce directly, bypassing all dialog/context code
      PsiExpression[] occurrences = (PsiExpression[]) java.lang.reflect.Array.newInstance(PsiExpression.class, 1);
      occurrences[0] = expression;
      Object result = Class.forName("com.intellij.refactoring.introduceVariable.VariableExtractor")
          .getMethod("introduce", Project.class, PsiExpression.class, Editor.class,
              PsiElement.class, PsiExpression[].class, settingsClass)
          .invoke(null, project, expression, editor, anchor, occurrences, settings);
      return result != null;
    } catch (Exception e) {
      Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
      LOG.warn("applyIntroduceVariable error: " + cause);
      return false;
    }
  }

  public static boolean isInlineAvailable(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    try {
      int offset = editor.getCaretModel().getOffset();
      PsiElement element = com.intellij.codeInsight.TargetElementUtil.findTargetElement(editor, offset);
      if (element == null) return false;
      for (com.intellij.lang.refactoring.InlineActionHandler handler :
           com.intellij.lang.refactoring.InlineActionHandler.EP_NAME.getExtensionList()) {
        if (handler.isEnabledOnElement(element, editor)) return true;
      }
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  public static boolean applyInline(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    try {
      int offset = editor.getCaretModel().getOffset();
      PsiElement element = com.intellij.codeInsight.TargetElementUtil.findTargetElement(editor, offset);
      if (element == null) return false;

      for (com.intellij.lang.refactoring.InlineActionHandler handler :
           com.intellij.lang.refactoring.InlineActionHandler.EP_NAME.getExtensionList()) {
        if (handler.isEnabledOnElement(element, editor)) {
          handler.inlineElement(project, editor, element);
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
      LOG.warn("applyInline error: " + cause);
      return false;
    }
  }

  public static boolean applyMove(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file,
                                   @Nullable String targetPackageUri) {
    try {
      if (targetPackageUri == null || targetPackageUri.isEmpty()) {
        LOG.warn("applyMove: targetPackageUri is required");
        return false;
      }
      int offset = editor.getCaretModel().getOffset();
      PsiElement element = file.findElementAt(offset);
      if (element == null) return false;

      PsiClass psiClass = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, PsiClass.class);
      if (psiClass == null) {
        LOG.warn("applyMove: no class found at position");
        return false;
      }

      // Resolve target directory
      String targetPath = targetPackageUri;
      if (targetPath.startsWith("file://")) targetPath = targetPath.substring(7);
      com.intellij.openapi.vfs.VirtualFile targetDirVf =
          com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(targetPath);
      if (targetDirVf == null || !targetDirVf.isDirectory()) {
        LOG.warn("applyMove: target directory not found: " + targetPackageUri);
        return false;
      }

      PsiDirectory targetDir = com.intellij.psi.PsiManager.getInstance(project).findDirectory(targetDirVf);
      if (targetDir == null) return false;

      PsiPackage targetPkg = com.intellij.psi.JavaDirectoryService.getInstance().getPackage(targetDir);
      if (targetPkg == null) {
        LOG.warn("applyMove: target directory has no package: " + targetPackageUri);
        return false;
      }

      // Build the new file content with updated package declaration
      String oldText = psiClass.getContainingFile().getText();
      String newPackage = targetPkg.getQualifiedName();
      String currentPackage = "";
      try {
        var pkgStmt = com.intellij.psi.util.PsiTreeUtil.findChildOfType(
            psiClass.getContainingFile(), com.intellij.psi.PsiPackageStatement.class);
        if (pkgStmt != null) {
          currentPackage = pkgStmt.getPackageName();
        }
      } catch (Exception ignored) {}

      String newText;
      if (!currentPackage.isEmpty()) {
        newText = oldText.replace("package " + currentPackage + ";", "package " + newPackage + ";");
      } else if (!newPackage.isEmpty()) {
        newText = "package " + newPackage + ";\n\n" + oldText;
      } else {
        newText = oldText;
      }
      final String finalNewText = newText;

      // Create the new file in the target directory
      String fileName = psiClass.getContainingFile().getName();
      com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> {
        try {
          // Delete existing file in target if present
          PsiFile existing = targetDir.findFile(fileName);
          if (existing != null) {
            existing.delete();
          }
          PsiFile newFile = targetDir.createFile(fileName);
          com.intellij.openapi.editor.Document document =
              com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(newFile.getVirtualFile());
          if (document != null) {
            document.setText(finalNewText);
          }
          // Delete the original file
          psiClass.getContainingFile().delete();
        } catch (Exception ex) {
          LOG.warn("applyMove: error during write action: " + ex, ex);
          throw new RuntimeException(ex);
        }
      });

      return true;
    } catch (Exception e) {
      Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
      LOG.warn("applyMove error: " + cause, cause);
      return false;
    }
  }

  public static boolean applySafeDelete(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    try {
      int offset = editor.getCaretModel().getOffset();
      PsiElement element = file.findElementAt(offset);
      if (element == null) return false;

      // Walk up to find the enclosing PsiElement (method, field, class, etc.)
      PsiElement target = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element,
          com.intellij.psi.PsiMethod.class, com.intellij.psi.PsiField.class,
          com.intellij.psi.PsiClass.class, com.intellij.psi.PsiVariable.class);
      if (target == null) {
        LOG.warn("applySafeDelete: no deletable element found at position");
        return false;
      }

      // Use SafeDeleteProcessor with the correct signature:
      // createInstance(Project, Runnable, PsiElement[], boolean, boolean)
      Class<?> processorClass = Class.forName(
          "com.intellij.refactoring.safeDelete.SafeDeleteProcessor");
      Method createInstance = processorClass.getMethod(
          "createInstance", Project.class, Runnable.class, PsiElement[].class, boolean.class, boolean.class);
      Object processor = createInstance.invoke(null, project,
          (Runnable) () -> {}, new PsiElement[]{target}, false, true);

      // Run the processor
      Method runMethod = processorClass.getMethod("run");
      runMethod.invoke(processor);
      return true;
    } catch (Exception e) {
      Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
      LOG.warn("applySafeDelete error: " + cause, cause);
      return false;
    }
  }
}
