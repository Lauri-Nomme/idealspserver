package tf.locals.idealsp.server.references;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class FindDefinitionCommand extends FindDefinitionCommandBase {
  private static final Logger LOG = Logger.getInstance(FindDefinitionCommand.class);

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
    if (element == null) {
      LOG.warn("resolveDefinitions: findElementAt returned null for offset=" + offset);
      return Stream.empty();
    }

    LOG.warn("resolveDefinitions: element=" + element.getClass().getSimpleName()
        + " text='" + element.getText() + "' offset=" + offset);

    PsiElement current = element;
    boolean sawReference = false;
    for (int i = 0; i < 20 && current != null; i++) {
      if (current instanceof PsiJavaCodeReferenceElement ref
          && ref.getParent() instanceof PsiNewExpression newExpr) {
        PsiMethod ctor = newExpr.resolveConstructor();
        if (ctor != null) return Stream.of(ctor);
      }
      if (current instanceof PsiPolyVariantReference pvr) {
        sawReference = true;
        ResolveResult[] results = pvr.multiResolve(false);
        if (results.length > 0) {
          LOG.warn("resolveDefinitions: multiResolve found " + results.length + " results");
          return Stream.of(results).map(ResolveResult::getElement).filter(Objects::nonNull);
        }
        // multiResolve failed, try single resolve
        if (current instanceof PsiReference pr) {
          PsiElement resolved = pr.resolve();
          if (resolved != null) {
            LOG.warn("resolveDefinitions: resolve() found " + resolved.getClass().getSimpleName());
            return Stream.of(resolved);
          }
        }
        // Try JavaPsiFacade fallback for class references
        if (current instanceof PsiJavaCodeReferenceElement codeRef) {
          PsiElement facadeResult = resolveViaFacade(codeRef, file);
          if (facadeResult != null) return Stream.of(facadeResult);
        }
      } else if (current instanceof PsiReference pr) {
        sawReference = true;
        PsiElement resolved = pr.resolve();
        if (resolved != null) {
          LOG.warn("resolveDefinitions: resolve() found " + resolved.getClass().getSimpleName());
          return Stream.of(resolved);
        }
      } else {
        PsiReference ref = current.getReference();
        if (ref != null) {
          sawReference = true;
          if (ref instanceof PsiPolyVariantReference pvr) {
            ResolveResult[] results = pvr.multiResolve(false);
            if (results.length > 0) {
              LOG.warn("resolveDefinitions: getReference().multiResolve found " + results.length + " results");
              return Stream.of(results).map(ResolveResult::getElement).filter(Objects::nonNull);
            }
          } else {
            PsiElement resolved = ref.resolve();
            if (resolved != null) {
              LOG.warn("resolveDefinitions: getReference().resolve() found " + resolved.getClass().getSimpleName());
              return Stream.of(resolved);
            }
          }
        }
      }
      // Only return the current element if it's a declaration AND we haven't seen a reference
      // (if we saw a reference that didn't resolve, returning the containing declaration is wrong)
      if (!sawReference && (current instanceof PsiClass || current instanceof PsiMethod
          || current instanceof PsiField || current instanceof PsiVariable)) {
        LOG.warn("resolveDefinitions: returning declaration " + current.getClass().getSimpleName());
        return Stream.of(current);
      }
      current = current.getParent();
    }
    LOG.warn("resolveDefinitions: no definition found after walking PSI tree");
    return Stream.empty();
  }

  @org.jetbrains.annotations.Nullable
  private static PsiElement resolveViaFacade(@NotNull PsiJavaCodeReferenceElement ref, @NotNull PsiFile file) {
    try {
      String qualifiedName = ref.getQualifiedName();
      if (qualifiedName == null) return null;
      LOG.warn("resolveDefinitions: trying JavaPsiFacade.findClass for " + qualifiedName);
      var project = file.getProject();
      var scope = com.intellij.psi.search.GlobalSearchScope.allScope(project);
      PsiClass found = JavaPsiFacade.getInstance(project).findClass(qualifiedName, scope);
      if (found != null) {
        LOG.warn("resolveDefinitions: JavaPsiFacade found " + found.getClass().getSimpleName());
        return found;
      }
    } catch (Exception e) {
      LOG.warn("resolveDefinitions: JavaPsiFacade fallback failed", e);
    }
    return null;
  }
}
