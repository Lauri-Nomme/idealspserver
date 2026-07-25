package tf.locals.idealsp.server.references;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;
import java.util.stream.Stream;

public class FindTypeDefinitionCommand extends FindDefinitionCommandBase {
  private static final Logger LOG = Logger.getInstance(FindTypeDefinitionCommand.class);

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

    LOG.warn("TypeDefinition: element=" + element.getClass().getSimpleName()
        + " text='" + element.getText() + "' offset=" + offset);

    // Walk up to find a named element with a type
    PsiElement current = element;
    for (int i = 0; i < 20 && current != null; i++) {
      try {
        PsiType type = null;
        if (current instanceof PsiVariable) {
          type = ((PsiVariable) current).getType();
          LOG.warn("TypeDefinition: found PsiVariable, type=" + (type != null ? type.getCanonicalText() : "null"));
        } else if (current instanceof PsiMethod) {
          type = ((PsiMethod) current).getReturnType();
          LOG.warn("TypeDefinition: found PsiMethod, returnType=" + (type != null ? type.getCanonicalText() : "null"));
        }

        if (type != null) {
          PsiClass resolvedType = PsiUtil.resolveClassInType(type);
          if (resolvedType != null) {
            LOG.warn("TypeDefinition: resolved to " + resolvedType.getQualifiedName());
            return Stream.of(resolvedType);
          }
          // Try JavaPsiFacade fallback
          if (type instanceof PsiClassType classType) {
            PsiClass refClass = classType.resolve();
            if (refClass != null) {
              String qualifiedName = refClass.getQualifiedName();
              if (qualifiedName != null) {
                PsiClass found = JavaPsiFacade.getInstance(project)
                    .findClass(qualifiedName, com.intellij.psi.search.GlobalSearchScope.allScope(project));
                if (found != null) {
                  LOG.warn("TypeDefinition: JavaPsiFacade found " + found.getQualifiedName());
                  return Stream.of(found);
                }
              }
            }
          }
        }

        // Try reference resolution (e.g., if we're on a type reference like MyTextDocumentService)
        if (current instanceof PsiJavaCodeReferenceElement ref) {
          PsiElement resolved = ref.resolve();
          if (resolved instanceof PsiClass psiClass) {
            LOG.warn("TypeDefinition: reference resolved to class " + psiClass.getQualifiedName());
            return Stream.of(psiClass);
          }
        }
        PsiReference ref = current.getReference();
        if (ref != null) {
          PsiElement resolved = ref.resolve();
          if (resolved != null) {
            if (resolved instanceof PsiVariable) {
              PsiClass resolvedType = PsiUtil.resolveClassInType(((PsiVariable) resolved).getType());
              if (resolvedType != null) return Stream.of(resolvedType);
            } else if (resolved instanceof PsiClass) {
              return Stream.of((PsiClass) resolved);
            }
          }
        }
      } catch (com.intellij.openapi.project.IndexNotReadyException e) {
        LOG.warn("TypeDefinition: IndexNotReadyException in dumb mode, skipping");
      }

      current = current.getParent();
    }

    LOG.warn("TypeDefinition: no type definition found");
    return Stream.empty();
  }
}
