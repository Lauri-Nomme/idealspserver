package tf.locals.idealsp.server.codeactions;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
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
      Class<?> methodExtractorClass = Class.forName("com.intellij.refactoring.extractMethod.newImpl.MethodExtractor");

      var selectionModel = editor.getSelectionModel();
      if (!selectionModel.hasSelection()) {
        Class<?> handlerClass = Class.forName("com.intellij.refactoring.extractMethod.ExtractMethodHandler");
        Method getElements = handlerClass.getMethod("getElements", Project.class, Editor.class, PsiFile.class);
        PsiElement[] elements = (PsiElement[]) getElements.invoke(null, project, editor, file);
        if (elements == null || elements.length == 0) return false;
        if (elements.length == 1) {
          var range = elements[0].getTextRange();
          selectionModel.setSelection(range.getStartOffset(), range.getEndOffset());
        } else {
          int start = Integer.MAX_VALUE, end = -1;
          for (PsiElement el : elements) {
            start = Math.min(start, el.getTextRange().getStartOffset());
            end = Math.max(end, el.getTextRange().getEndOffset());
          }
          if (start < end) selectionModel.setSelection(start, end);
        }
      }

      Object methodExtractor = methodExtractorClass.getDeclaredConstructor().newInstance();
      Method doTestExtract = methodExtractorClass.getMethod("doTestExtract",
          boolean.class, Editor.class, Boolean.class, Boolean.class,
          com.intellij.psi.PsiType.class, String.class,
          com.intellij.psi.PsiClass.class, String.class, int[].class);
      boolean result = (boolean) doTestExtract.invoke(methodExtractor,
          true, editor, Boolean.FALSE, Boolean.FALSE, null, null, null, "private", new int[0]);
      return result;
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

  private static @Nullable PsiElement findInlineTarget(@NotNull PsiFile file, int offset) {
    PsiElement element = file.findElementAt(offset);
    if (element == null) return null;

    // Walk up from the leaf identifier to find a reference expression or method call
    PsiElement candidate = element;
    while (candidate != null) {
      String cn = candidate.getClass().getName();
      if (cn.contains("PsiReferenceExpression") ||
          cn.contains("PsiMethodCallExpression") ||
          cn.contains("PsiNewExpression")) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    return null;
  }

  public static boolean isInlineAvailable(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    try {
      int offset = editor.getCaretModel().getOffset();
      PsiElement target = findInlineTarget(file, offset);
      return target != null;
    } catch (Exception e) {
      return false;
    }
  }

  public static boolean applyInline(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    try {
      int offset = editor.getCaretModel().getOffset();
      PsiElement target = findInlineTarget(file, offset);
      if (target == null) return false;

      // Inline the target using the appropriate handler
      Class<?> handlerClass = Class.forName("com.intellij.refactoring.inline.InlineHandler");
      Method invokeMethod = handlerClass.getMethod("invoke", Project.class, Editor.class, PsiElement.class);
      invokeMethod.invoke(null, project, editor, target);
      return true;
    } catch (Exception e) {
      Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
      LOG.warn("applyInline error: " + cause);
      return false;
    }
  }
}
