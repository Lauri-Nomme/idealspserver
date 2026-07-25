package tf.locals.idealsp.server.references;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;
import java.util.stream.Stream;

public class FindTypeDefinitionCommand extends FindDefinitionCommandBase {
  public FindTypeDefinitionCommand(@NotNull Position pos) {
    super(pos);
  }

  @Override
  protected @NotNull Supplier<@NotNull String> getMessageSupplier() {
    return () -> "TypeDefinition call";
  }

  @Override
  protected @NotNull Stream<PsiElement> findDefinitions(@NotNull Editor editor, int offset) {
    Project project = editor.getProject();
    if (project == null) return Stream.empty();
    PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
    if (file == null) return Stream.empty();

    PsiElement element = file.findElementAt(offset);
    if (element == null) return Stream.empty();

    // Walk up to find a named element with a type
    PsiElement current = element;
    for (int i = 0; i < 20 && current != null; i++) {
      PsiType type = null;
      if (current instanceof PsiVariable) {
        type = ((PsiVariable) current).getType();
      } else if (current instanceof PsiMethod) {
        type = ((PsiMethod) current).getReturnType();
      }

      if (type != null) {
        PsiClass resolvedType = PsiUtil.resolveClassInType(type);
        if (resolvedType != null) return Stream.of(resolvedType);
      }

      // Try reference resolution
      PsiReference ref = current.getReference();
      if (ref != null) {
        PsiElement resolved = ref.resolve();
        if (resolved != null) {
          if (resolved instanceof PsiVariable) {
            PsiClass resolvedType = PsiUtil.resolveClassInType(((PsiVariable) resolved).getType());
            if (resolvedType != null) return Stream.of(resolvedType);
          }
        }
      }

      current = current.getParent();
    }

    return Stream.empty();
  }
}
