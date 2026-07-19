package tf.locals.idealsp.server.projectstructure;

import com.intellij.facet.FacetManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class ModuleCollector {
  private static final Logger LOG = Logger.getInstance(ModuleCollector.class);

  static @NotNull List<ModuleInfo> collect(@NotNull Project project, @Nullable String scope) {
    Module[] modules = ModuleManager.getInstance(project).getModules();
    List<ModuleInfo> result = new ArrayList<>();
    for (Module module : modules) {
      if ("entry".equals(scope)) continue;
      result.add(collectModuleInfo(module));
    }
    return result;
  }

  static @NotNull DependencyGraph buildGraph(@NotNull Project project, @Nullable String scope) {
    if ("source".equals(scope) || "entry".equals(scope)) {
      return new DependencyGraph(List.of());
    }
    Module[] modules = ModuleManager.getInstance(project).getModules();
    List<DependencyEdge> edges = new ArrayList<>();
    Set<String> moduleNames = new HashSet<>();
    for (Module m : modules) moduleNames.add(m.getName());

    for (Module module : modules) {
      for (OrderEntry entry : ModuleRootManager.getInstance(module).getOrderEntries()) {
        if (entry instanceof ModuleOrderEntry) {
          ModuleOrderEntry modEntry = (ModuleOrderEntry) entry;
          String depModuleName = modEntry.getModuleName();
          if (depModuleName != null && moduleNames.contains(depModuleName)) {
            String scopeLabel = "COMPILE";
            DependencyScope depScope = modEntry.getScope();
            if (depScope != null) {
              switch (depScope.toString()) {
                case "TEST" -> scopeLabel = "TEST";
                case "RUNTIME" -> scopeLabel = "RUNTIME";
                case "PROVIDED" -> scopeLabel = "PROVIDED";
              }
            }
            edges.add(new DependencyEdge(module.getName(), depModuleName, scopeLabel));
          }
        }
      }
    }
    return new DependencyGraph(edges);
  }

  private static @NotNull ModuleInfo collectModuleInfo(@NotNull Module module) {
    ModuleInfo info = new ModuleInfo();
    info.setName(module.getName());
    info.setType(detectModuleType(module));

    ModuleRootManager rootManager = ModuleRootManager.getInstance(module);

    List<String> contentRoots = new ArrayList<>();
    for (VirtualFile root : rootManager.getContentRoots()) {
      contentRoots.add(root.getPath());
    }
    info.setContentRoots(contentRoots);

    Sdk sdk = rootManager.getSdk();
    if (sdk != null) {
      info.setSdk(sdk.getName());
    }

    List<String> facets = new ArrayList<>();
    try {
      FacetManager fm = FacetManager.getInstance(module);
      for (var facet : fm.getSortedFacets()) {
        facets.add(facet.getType().getStringId());
      }
    } catch (Exception e) {
      LOG.warn("Failed to get facets for module: " + module.getName(), e);
    }
    info.setFacets(facets);

    List<String> libDeps = new ArrayList<>();
    for (OrderEntry entry : rootManager.getOrderEntries()) {
      if (entry instanceof LibraryOrderEntry) {
        LibraryOrderEntry libEntry = (LibraryOrderEntry) entry;
        String libName = libEntry.getLibraryName();
        if (libName != null) {
          libDeps.add(libName);
        }
      }
    }
    info.setLibraryDependencies(libDeps);

    return info;
  }

  private static @NotNull String detectModuleType(@NotNull Module module) {
    try {
      FacetManager fm = FacetManager.getInstance(module);
      for (var facet : fm.getSortedFacets()) {
        String typeId = facet.getType().getStringId();
        if (typeId != null) {
          String lower = typeId.toLowerCase();
          if (lower.contains("android")) return "ANDROID_MODULE";
          if (lower.contains("web") || lower.contains("spring")) return "WEB_MODULE";
        }
      }
    } catch (Exception ignored) {}
    return "JAVA_MODULE";
  }
}
