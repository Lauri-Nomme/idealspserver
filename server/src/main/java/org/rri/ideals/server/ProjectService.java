package org.rri.ideals.server;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener;
import com.intellij.util.concurrency.AppExecutorUtil;

public class ProjectService {
  private final static Logger LOG = Logger.getInstance(ProjectService.class);

  private final Map<LspPath, String> projectHashes = new HashMap<>();

  @NotNull
  public static ProjectService getInstance() {
    return ApplicationManager.getApplication().getService(ProjectService.class);
  }

  @NotNull
  public Project resolveProjectFromRoot(@NotNull LspPath root) {
    // TODO: in-memory virtual files for testing have temp:/// prefix, figure out how to resolve the document from them
    // otherwise it gets confusing to have to look up the line and column being tested in the test document

    if (!Files.isDirectory(root.toPath())) {
      throw new IllegalArgumentException("Isn't a directory: " + root);
    }

    return ensureProject(root);
  }

  public void closeProject(@NotNull Project project) {
    if (projectHashes.values().remove(project.getLocationHash())) {
      LOG.info("Closing project: " + project);
      var closed = new boolean[]{false};
      ApplicationManager.getApplication().invokeAndWait(() -> closed[0] = ProjectManagerEx.getInstanceEx().forceCloseProject(project));
      if(!closed[0]) {
        LOG.warn("Closing project: Project wasn't closed: " + project);
      }
    } else {
      LOG.warn("Closing project: Project wasn't opened by LSP server; do nothing: " + project);
    }
  }

  @NotNull
  private Project ensureProject(@NotNull LspPath projectPath) {
    var project = getProject(projectPath);
    if (project == null)
      throw new IllegalArgumentException("Couldn't find document at " + projectPath);
    if (project.isDisposed())
      throw new IllegalArgumentException("Project was already disposed: " + project);

    return project;
  }

  @Nullable
  private Project getProject(@NotNull LspPath projectPath) {

    final var mgr = ProjectManagerEx.getInstanceEx();

    final var projectHash = projectHashes.get(projectPath);
    if (projectHash != null) {
      Project project = mgr.findOpenProjectByHash(projectHash);
      if (project != null && !project.isDisposed()) {
        return project;
      } else {
        LOG.info("Cached document was disposed, reopening: " + projectPath);
      }
    }

    if (!Files.exists(projectPath.toPath())) {  // todo VirtualFile?
      LOG.warn("Project path doesn't exist: " + projectPath);
      return null;
    }

    var project = findOrLoadProject(projectPath, mgr);

    if (project != null) {
      waitUntilInitialized(project);
      ensureSourceRoots(project, projectPath);
      registerSourceRootProtection(project, projectPath);
      cacheProject(projectPath, project);
    }

    return project;
  }

  @SuppressWarnings("UnstableApiUsage")
  @Nullable
  private Project findOrLoadProject(@NotNull LspPath projectPath, @NotNull ProjectManagerEx mgr) {
    return Arrays.stream(mgr.getOpenProjects())
        .filter(it -> LspPath.fromLocalPath(Paths.get(Objects.requireNonNull(it.getBasePath()))).equals(projectPath))
        .findFirst()
        .orElseGet(() -> mgr.openProject(projectPath.toPath(),
            new OpenProjectTask(false, null, false, false).withForceOpenInNewFrame(true)));

  }

  private void waitUntilInitialized(@NotNull Project project) {
    try {
      // Wait until the project is initialized to prevent invokeAndWait hangs
      // todo avoid
      while (!project.isInitialized()) {
        //noinspection BusyWait
        Thread.sleep(100);
      }
    } catch (InterruptedException e) {
      LOG.warn("Interrupted while waiting for project to be initialized: " + project.getBasePath(), e);
      throw new RuntimeException(e);
    }
  }

  private void cacheProject(@NotNull LspPath projectPath, Project project) {
    LOG.info("Caching project: " + projectPath);
    projectHashes.put(projectPath, project.getLocationHash());
  }

  /**
   * Register listeners to re-apply source roots if they are wiped by async project initialization.
   * After project open, external system plugins (Gradle, Maven) may run async import/sync that
   * replaces the entire module structure, wiping manually-added content/source roots.
   * We listen for import completion and also schedule a delayed fallback re-check.
   */
  private void registerSourceRootProtection(@NotNull Project project, @NotNull LspPath projectPath) {
    // Listen for external system (Gradle/Maven) import completion
    try {
      project.getMessageBus().connect().subscribe(
          ProjectDataImportListener.TOPIC,
          new ProjectDataImportListener() {
            @Override
            public void onImportFinished(@Nullable String path) {
              LOG.info("External system import finished, re-checking source roots");
              AppExecutorUtil.getAppExecutorService().execute(() -> {
                if (!project.isDisposed()) {
                  ensureSourceRoots(project, projectPath);
                }
              });
            }

            @Override
            public void onImportFailed(@Nullable String path, @NotNull Throwable t) {
              LOG.info("External system import failed (" + t.getMessage() + "), re-checking source roots");
              AppExecutorUtil.getAppExecutorService().execute(() -> {
                if (!project.isDisposed()) {
                  ensureSourceRoots(project, projectPath);
                }
              });
            }
          }
      );
    } catch (Exception e) {
      LOG.warn("Failed to subscribe to ProjectDataImportListener", e);
    }

    // Fallback: schedule multiple delayed re-checks to catch root wipe at different timings.
    // The wipe happens between ~6-26s after project open (exact timing varies).
    // Multiple checks ensure we catch it quickly without knowing the exact timing.
    for (int delaySec : new int[]{5, 15, 30}) {
      final int delay = delaySec;
      AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
        if (!project.isDisposed()) {
          LOG.info("Delayed source root re-check (" + delay + "s after project open)");
          ensureSourceRoots(project, projectPath);
        }
      }, delay, TimeUnit.SECONDS);
    }
  }

  /**
   * Ensure the project has at least one module with the workspace folder as a content/source root.
   * Without this, GlobalSearchScope (which relies on IntelliJ's word index) won't find any
   * project files, breaking cross-file references, find usages, etc.
   */
  private void ensureSourceRoots(@NotNull Project project, @NotNull LspPath projectPath) {
    var moduleManager = ModuleManager.getInstance(project);
    var modules = moduleManager.getModules();

    // Check if any module already has content roots
    for (var module : modules) {
      var rootManager = ModuleRootManager.getInstance(module);
      var contentRoots = rootManager.getContentRoots();
      if (contentRoots.length > 0) {
        return;
      }
    }

    // No content roots found - add the workspace folder as a source root
    var projectDir = VirtualFileManager.getInstance().findFileByUrl(projectPath.toLspUri());
    if (projectDir == null) {
      projectDir = projectPath.refreshAndFindVirtualFile();
    }
    if (projectDir == null) {
      LOG.warn("Cannot find virtual file for project path: " + projectPath);
      return;
    }

    LOG.info("Setting up source roots for workspace folder: " + projectPath);
    final var dir = projectDir;
    ApplicationManager.getApplication().invokeAndWait(() ->
      ApplicationManager.getApplication().runWriteAction(() -> {
        try {
          var module = modules.length > 0 ? modules[0]
              : moduleManager.newModule(
                  Files.createTempDirectory("ideals-lsp-").resolve("lsp-module.iml"),
                  "JAVA_MODULE");
          var modifiableModel = ModuleRootManager.getInstance(module).getModifiableModel();
          ContentEntry contentEntry = modifiableModel.addContentEntry(dir);
          contentEntry.addSourceFolder(dir, false);
          modifiableModel.commit();
          LOG.info("Added source root: " + dir.getUrl());
        } catch (Exception e) {
          LOG.warn("Failed to set up source roots for workspace: " + projectPath, e);
        }
      })
    );
  }
}
