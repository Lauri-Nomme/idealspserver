package tf.locals.idealsp.server.typehierarchy;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class SubtypesCommand {

  private final @NotNull TypeHierarchyItem item;

  public SubtypesCommand(@NotNull TypeHierarchyItem item) {
    this.item = item;
  }

  public List<TypeHierarchyItem> execute(@NotNull Project project) {
    List<TypeHierarchyItem> result = new ArrayList<>();

    PsiClass psiClass = PrepareTypeHierarchyCommand.resolveClassFromItem(project, item);
    if (psiClass == null) return result;

    GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
    ClassInheritorsSearch.search(psiClass, scope, false).forEach(inheritor -> {
      PsiFile file = inheritor.getContainingFile();
      if (file != null) {
        TypeHierarchyItem childItem = PrepareTypeHierarchyCommand.convertToTypeHierarchyItem(inheritor, file);
        if (childItem != null) result.add(childItem);
      }
      return true;
    });

    return result;
  }
}
