package tf.locals.idealsp.server.typehierarchy;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.jetbrains.annotations.NotNull;
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.ArrayList;
import java.util.List;

public class SupertypesCommand {
  private static final Logger LOG = Logger.getInstance(SupertypesCommand.class);

  private final @NotNull TypeHierarchyItem item;

  public SupertypesCommand(@NotNull TypeHierarchyItem item) {
    this.item = item;
  }

  public List<TypeHierarchyItem> execute(@NotNull Project project) {
    List<TypeHierarchyItem> result = new ArrayList<>();

    PsiClass psiClass = PrepareTypeHierarchyCommand.resolveClassFromItem(project, item);
    if (psiClass == null) return result;

    PsiClass superClass = psiClass.getSuperClass();
    if (superClass != null) {
      PsiFile file = superClass.getContainingFile();
      if (file != null) {
        TypeHierarchyItem superItem = PrepareTypeHierarchyCommand.convertToTypeHierarchyItem(superClass, file);
        if (superItem != null) result.add(superItem);
      }
    }

    for (PsiClass iface : psiClass.getInterfaces()) {
      PsiFile file = iface.getContainingFile();
      if (file != null) {
        TypeHierarchyItem ifaceItem = PrepareTypeHierarchyCommand.convertToTypeHierarchyItem(iface, file);
        if (ifaceItem != null) result.add(ifaceItem);
      }
    }

    return result;
  }
}
