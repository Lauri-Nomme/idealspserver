package tf.locals.idealsp.server.references;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class FindImplementationCommand extends FindDefinitionCommandBase {
  public FindImplementationCommand(@NotNull Position pos) {
    super(pos);
  }

  @Override
  protected @NotNull Supplier<@NotNull String> getMessageSupplier() {
    return () -> "Implementation call";
  }

  @Override
  protected @NotNull Stream<PsiElement> findDefinitions(@NotNull Editor editor, int offset) {
    Project project = editor.getProject();
    if (project == null) return Stream.empty();
    PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
    if (file == null) return Stream.empty();

    PsiElement element = file.findElementAt(offset);
    if (element == null) return Stream.empty();

    // Walk up to find a method or class declaration
    PsiElement current = element;
    for (int i = 0; i < 20 && current != null; i++) {
      if (current instanceof PsiMethod method) {
        List<PsiElement> results = new ArrayList<>();
        results.add(method);
        OverridingMethodsSearch.search(method, true).forEach(overriding -> {
          results.add(overriding);
          return true;
        });
        return results.stream();
      }
      if (current instanceof PsiClass psiClass) {
        if (psiClass.isInterface()) {
          List<PsiElement> results = new ArrayList<>();
          results.add(psiClass);
          ClassInheritorsSearch.search(psiClass, true).forEach(inheritor -> {
            results.add(inheritor);
            return true;
          });
          return results.stream();
        }
        return Stream.of(psiClass);
      }
      current = current.getParent();
    }

    return Stream.empty();
  }
}
