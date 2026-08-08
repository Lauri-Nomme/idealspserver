package tf.locals.idealsp.server.typehierarchy;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
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

    // projectScope excludes files that are not registered content/source roots, which
    // can happen for test-data fixtures on a freshly-initialized machine. Include the
    // base class's own file so inheritors in that file are always found.
    GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
    VirtualFile file = psiClass.getContainingFile() == null ? null : psiClass.getContainingFile().getVirtualFile();
    if (file != null) {
      scope = scope.union(GlobalSearchScope.fileScope(project, file));
    }
    GlobalSearchScope searchScope = scope;
    ClassInheritorsSearch.search(psiClass, searchScope, false).forEach(inheritor -> {
      PsiFile inheritorFile = inheritor.getContainingFile();
      if (inheritorFile != null) {
        TypeHierarchyItem childItem = PrepareTypeHierarchyCommand.convertToTypeHierarchyItem(inheritor, inheritorFile);
        if (childItem != null) result.add(childItem);
      }
      return true;
    });

    return result;
  }
}
