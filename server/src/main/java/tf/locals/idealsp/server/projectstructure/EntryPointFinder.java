package tf.locals.idealsp.server.projectstructure;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class EntryPointFinder {
  private static final Logger LOG = Logger.getInstance(EntryPointFinder.class);

  static @NotNull List<EntryPoint> find(@NotNull Project project, @Nullable String scope) {
    if ("modules".equals(scope) || "source".equals(scope)) {
      return List.of();
    }
    List<EntryPoint> result = new ArrayList<>();
    PsiManager psiManager = PsiManager.getInstance(project);

    for (Module module : ModuleManager.getInstance(project).getModules()) {
      ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
      for (ContentEntry entry : rootManager.getContentEntries()) {
        for (SourceFolder folder : entry.getSourceFolders()) {
          VirtualFile root = folder.getFile();
          if (root == null) continue;
          boolean isTestRoot = folder.getRootType().toString().contains("TEST");
          scanForEntryPoints(psiManager, root, module.getName(), result, isTestRoot);
        }
      }
    }
    return result;
  }

  private static void scanForEntryPoints(PsiManager psiManager, VirtualFile root, String moduleName,
                                          List<EntryPoint> result, boolean isTestRoot) {
    VfsUtilCore.iterateChildrenRecursively(root, file -> file.isDirectory() || file.getName().endsWith(".java"), file -> {
      if (file.isDirectory()) return true;
      PsiFile psiFile = psiManager.findFile(file);
      if (!(psiFile instanceof PsiJavaFile javaFile)) return true;
      String fileUri = "file://" + file.getPath();

      for (PsiClass psiClass : javaFile.getClasses()) {
        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName == null) qualifiedName = psiClass.getName();

        for (PsiMethod method : psiClass.getMethods()) {
          if (isMainMethod(method)) {
            EntryPoint ep = new EntryPoint();
            ep.setKind("main");
            ep.setName("main");
            ep.setQualifiedName(qualifiedName + ".main");
            ep.setFile(fileUri);
            ep.setLine(method.getTextOffset() > 0
                ? getLineNumber(file, method.getTextOffset()) : 0);
            ep.setModule(moduleName);
            result.add(ep);
          }
        }

        if (hasTestAnnotation(psiClass)) {
          for (PsiMethod method : psiClass.getMethods()) {
            if (isTestMethod(method)) {
              EntryPoint ep = new EntryPoint();
              ep.setKind(isTestRoot ? "test" : "main");
              ep.setName(method.getName());
              ep.setQualifiedName(qualifiedName + "." + method.getName());
              ep.setFile(fileUri);
              ep.setLine(method.getTextOffset() > 0
                  ? getLineNumber(file, method.getTextOffset()) : 0);
              ep.setModule(moduleName);
              result.add(ep);
            }
          }
        }

        if (hasFrameworkApplicationAnnotation(psiClass)) {
          EntryPoint ep = new EntryPoint();
          ep.setKind("application");
          ep.setName(psiClass.getName());
          ep.setQualifiedName(qualifiedName);
          ep.setFile(fileUri);
          ep.setLine(psiClass.getTextOffset() > 0
              ? getLineNumber(file, psiClass.getTextOffset()) : 0);
          ep.setModule(moduleName);
          result.add(ep);
        }
      }
      return true;
    });
  }

  private static boolean isMainMethod(PsiMethod method) {
    if (!"main".equals(method.getName())) return false;
    if (!method.hasModifierProperty(PsiModifier.PUBLIC)) return false;
    if (!method.hasModifierProperty(PsiModifier.STATIC)) return false;
    PsiType returnType = method.getReturnType();
    if (returnType == null || !PsiType.VOID.equals(returnType)) return false;
    PsiParameter[] params = method.getParameterList().getParameters();
    if (params.length != 1) return false;
    PsiType paramType = params[0].getType();
    return paramType.equalsToText("java.lang.String[]");
  }

  private static boolean isTestMethod(PsiMethod method) {
    for (PsiAnnotation ann : method.getAnnotations()) {
      String qname = ann.getQualifiedName();
      if (qname != null) {
        if (qname.endsWith(".Test") || qname.endsWith(".ParameterizedTest")
            || qname.endsWith(".SpringBootTest")) {
          return true;
        }
      } else {
        String text = ann.getText();
        if (text.contains("Test")) return true;
      }
    }
    return false;
  }

  private static boolean hasTestAnnotation(PsiClass psiClass) {
    for (PsiAnnotation ann : psiClass.getAnnotations()) {
      String qname = ann.getQualifiedName();
      if (qname != null) {
        if (qname.endsWith(".Test") || qname.endsWith(".SpringBootTest")) {
          return true;
        }
      } else {
        String text = ann.getText();
        if (text.contains("SpringBootTest") || text.contains("Test")) return true;
      }
    }
    return false;
  }

  private static boolean hasFrameworkApplicationAnnotation(PsiClass psiClass) {
    String[] frameworkAnnotations = {
      "SpringBootApplication", "MicronautApplication", "QuarkusApplication",
      "Application", "SpringBootConfiguration"
    };
    for (PsiAnnotation ann : psiClass.getAnnotations()) {
      String qname = ann.getQualifiedName();
      if (qname != null) {
        for (String name : frameworkAnnotations) {
          if (qname.endsWith("." + name) || qname.equals(name)) return true;
        }
      } else {
        String text = ann.getText();
        for (String name : frameworkAnnotations) {
          if (text.contains(name)) return true;
        }
      }
    }
    return false;
  }

  private static int getLineNumber(VirtualFile file, int offset) {
    try {
      byte[] content;
      content = file.contentsToByteArray();
      int line = 0;
      for (int i = 0; i < offset && i < content.length; i++) {
        if (content[i] == '\n') line++;
      }
      return line;
    } catch (Exception e) {
      return 0;
    }
  }
}
