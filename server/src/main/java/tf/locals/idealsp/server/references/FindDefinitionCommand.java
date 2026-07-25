package tf.locals.idealsp.server.references;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;
import java.util.stream.Stream;

public class FindDefinitionCommand extends FindDefinitionCommandBase {
  public FindDefinitionCommand(@NotNull Position pos) {
    super(pos);
  }

  @Override
  protected @NotNull Supplier<@NotNull String> getMessageSupplier() {
    return () -> "Definition call";
  }

  @Override
  protected @NotNull Stream<PsiElement> findDefinitions(@NotNull Editor editor, int offset) {
    Project project = editor.getProject();
    if (project == null) return Stream.empty();
    PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
    if (file == null) return Stream.empty();

    PsiElement element = file.findElementAt(offset);
    if (element == null) return Stream.empty();

    PsiElement current = element;
    for (int i = 0; i < 20 && current != null; i++) {
      PsiReference ref = current.getReference();
      if (ref != null) {
        PsiElement resolved = ref.resolve();
        if (resolved != null) return Stream.of(resolved);
      }
      if (current instanceof PsiClass || current instanceof PsiMethod || current instanceof PsiField ||
          current instanceof PsiVariable) {
        return Stream.of(current);
      }
      current = current.getParent();
    }

    return Stream.empty();
  }
}
