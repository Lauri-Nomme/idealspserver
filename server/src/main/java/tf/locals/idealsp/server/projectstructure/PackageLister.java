package tf.locals.idealsp.server.projectstructure;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class PackageLister {
  private static final Logger LOG = Logger.getInstance(PackageLister.class);

  static @NotNull List<SourceRootInfo> list(@NotNull Project project, @Nullable String scope) {
    if ("entry".equals(scope) || "modules".equals(scope)) {
      return List.of();
    }
    List<SourceRootInfo> result = new ArrayList<>();

    for (Module module : ModuleManager.getInstance(project).getModules()) {
      ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
      for (ContentEntry entry : rootManager.getContentEntries()) {
        for (SourceFolder folder : entry.getSourceFolders()) {
          VirtualFile root = folder.getFile();
          if (root == null) continue;

          SourceRootInfo sri = new SourceRootInfo();
          sri.setModule(module.getName());
          sri.setPath(root.getPath());
          sri.setType(mapRootType(folder));
          sri.setRootFor(mapRootType(folder));

          List<String> packages = new ArrayList<>();
          for (VirtualFile child : root.getChildren()) {
            if (child.isDirectory() && containsJavaFiles(child)) {
              String packageName = folder.getPackagePrefix();
              if (!packageName.isEmpty() && !packageName.equals(child.getName())) {
                packageName = packageName + "." + child.getName();
              } else {
                packageName = child.getName();
              }
              packages.add(packageName);
            }
          }
          sri.setPackages(packages);
          result.add(sri);
        }
      }
    }
    return result;
  }

  private static boolean containsJavaFiles(VirtualFile dir) {
    for (VirtualFile child : dir.getChildren()) {
      if (child.isDirectory()) {
        if (containsJavaFiles(child)) return true;
      } else if (child.getName().endsWith(".java")) {
        return true;
      }
    }
    return false;
  }

  private static @NotNull String mapRootType(@NotNull SourceFolder folder) {
    var type = folder.getRootType();
    if (type == null) return "SOURCE";
    String name = type.toString();
    if (name.contains("TEST")) return "TEST";
    if (name.contains("RESOURCE")) return "RESOURCE";
    if (name.contains("GENERATED")) return "GENERATED";
    return "SOURCE";
  }
}
