package tf.locals.idealsp.server.references;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
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
    final var reference = TargetElementUtil.findReference(editor, offset);
    final var flags = TargetElementUtil.getInstance().getDefinitionSearchFlags();
    final var targetElement = TargetElementUtil.getInstance().findTargetElement(editor, flags, offset);
    if (targetElement != null) return Stream.of(targetElement);
    if (reference != null) {
      var candidates = TargetElementUtil.getInstance().getTargetCandidates(reference);
      if (!candidates.isEmpty()) return candidates.stream();
    }

    // TargetElementUtil failed (e.g., no DataContext in headless mode).
    // Fall back to direct PSI resolution.
    Project project = editor.getProject();
    if (project == null) return Stream.empty();
    PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
    if (file == null) return Stream.empty();
    return resolveDefinitions(file, offset);
  }

  static @NotNull Stream<PsiElement> resolveDefinitions(@NotNull PsiFile file, int offset) {
    PsiElement element = file.findElementAt(offset);
    if (element == null) return Stream.empty();

    PsiElement current = element;
    for (int i = 0; i < 20 && current != null; i++) {
      if (current instanceof PsiJavaCodeReferenceElement ref
          && ref.getParent() instanceof PsiNewExpression newExpr) {
        PsiMethod ctor = newExpr.resolveConstructor();
        if (ctor != null) return Stream.of(ctor);
      }
      if (current instanceof PsiPolyVariantReference pvr) {
        ResolveResult[] results = pvr.multiResolve(false);
        if (results.length > 0) {
          return Stream.of(results).map(ResolveResult::getElement).filter(Objects::nonNull);
        }
      } else if (current instanceof PsiReference pr) {
        PsiElement resolved = pr.resolve();
        if (resolved != null) return Stream.of(resolved);
      } else {
        PsiReference ref = current.getReference();
        if (ref != null) {
          if (ref instanceof PsiPolyVariantReference pvr) {
            ResolveResult[] results = pvr.multiResolve(false);
            if (results.length > 0) {
              return Stream.of(results).map(ResolveResult::getElement).filter(Objects::nonNull);
            }
          } else {
            PsiElement resolved = ref.resolve();
            if (resolved != null) return Stream.of(resolved);
          }
        }
      }
      if (current instanceof PsiClass || current instanceof PsiMethod
          || current instanceof PsiField || current instanceof PsiVariable) {
        return Stream.of(current);
      }
      current = current.getParent();
    }
    return Stream.empty();
  }
}
