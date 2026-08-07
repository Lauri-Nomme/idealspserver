package tf.locals.idealsp.server;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener;
import com.intellij.util.concurrency.AppExecutorUtil;
import tf.locals.idealsp.server.util.MiscUtil;

@Service(Service.Level.APP)
final public class ProjectService {
  private final static Logger LOG = Logger.getInstance(ProjectService.class);

  private final Map<LspPath, String> projectHashes = new HashMap<>();

  /**
   * Projects whose external system (Gradle/Maven) import/sync is currently in progress.
   * While a project is in this set, its workspace-model update can hold a write permit and
   * starve concurrent LSP reads on the writer-preferring lock, so LSP queries are rejected
   * with a clear error instead of hanging until the client times out.
   */
  private final Set<Project> importingProjects = ConcurrentHashMap.newKeySet();

  /** How long a query tolerates a pending write action before being rejected as importing. */
  private static final long PENDING_WRITE_GRACE_MS = 3000;

  public ProjectService() {
    // Subscribe the Gradle sync gate as soon as any project opens — BEFORE getProject() runs
    // its post-open setup (waitUntilInitialized/ensureSourceRoots), which can be slower than
    // the sync's own early model-fetch events. Without this, the initial sync's events are
    // missed and importingProjects stays empty during the whole sync window.
    try {
      ApplicationManager.getApplication().getMessageBus().connect().subscribe(
          ProjectManager.TOPIC,
          new ProjectManagerListener() {
            @Override
            public void projectOpened(@NotNull Project project) {
              registerGradleSyncGate(project);
            }
          });
    } catch (Exception e) {
      LOG.warn("Failed to subscribe Gradle sync gate at ProjectService construction", e);
    }
  }

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

  /**
   * @return true while the project's external build system (Gradle/Maven) is importing/syncing.
   */
  public boolean isImportInProgress(@NotNull Project project) {
    return importingProjects.contains(project);
  }

  /**
   * Rejects an LSP request with a clear error if the project is still importing/syncing.
   * During the sync window IntelliJ's workspace-model update holds a write permit that
   * starves concurrent LSP reads (writer-preferring lock), so failing fast is preferable to
   * blocking until the client times out.
   *
   * <p>Two signals are checked:
   * <ol>
   *   <li>known external-system import in progress ({@link ProjectDataImportListener});</li>
   *   <li>a write action that stays pending — the actual condition that starves readers on
   *       the writer-preferring lock. The Gradle sync in 2026.1 runs via
   *       {@code GradleSyncActionRunner/WorkspaceModelImpl} and does <em>not</em> publish
   *       {@link ProjectDataImportListener} events, so this direct check is required.
   *       Brief writes are tolerated by a short grace period.</li>
   * </ol>
   */
  public void ensureImportFinished(@NotNull Project project) {
    if (importingProjects.contains(project)) {
      throw new ProjectImportInProgressException(project);
    }
    if (!isWriteActionPending()) {
      return;
    }
    var deadline = System.currentTimeMillis() + PENDING_WRITE_GRACE_MS;
    while (isWriteActionPending()) {
      if (System.currentTimeMillis() >= deadline) {
        throw new ProjectImportInProgressException(project);
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted while waiting for pending write action", e);
      }
    }
  }

  /**
   * IntelliJ 2026.1 exposes the pending-write flag only on {@code ApplicationImpl}, not on
   * the public {@code Application} interface, so it is read reflectively.
   */
  private boolean isWriteActionPending() {
    try {
      var method = ApplicationManager.getApplication().getClass().getMethod("isWriteActionPending");
      return Boolean.TRUE.equals(method.invoke(ApplicationManager.getApplication()));
    } catch (Exception e) {
      LOG.warn("Cannot query isWriteActionPending(); disabling write-pending guard", e);
      return false;
    }
  }

  /**
   * Fast, non-blocking check: true while the project is known to be importing/syncing or a
   * write action is pending (which would starve a read on the writer-preferring lock).
   * Used by fire-and-forget work (diagnostics) that can simply be skipped and retried later.
   */
  public boolean isImportInProgressOrWritePending(@NotNull Project project) {
    if (importingProjects.contains(project)) {
      return true;
    }
    return isWriteActionPending();
  }

  /**
   * Blocks until the project is not importing/syncing and no write action is pending, or
   * until {@code timeoutMs} elapses. Holds no locks while waiting. Returns true if the
   * project became stable, false on timeout/interruption. Used by fire-and-forget work
   * (diagnostics) that must not run daemon highlighting while the sync holds the lock.
   */
  public boolean waitForProjectStability(@NotNull Project project, long timeoutMs) {
    var deadline = System.currentTimeMillis() + timeoutMs;
    while (isImportInProgressOrWritePending(project)) {
      if (System.currentTimeMillis() >= deadline) {
        return false;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return true;
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
            public void onImportStarted(@Nullable String path) {
              importingProjects.add(project);
            }

            @Override
            public void onImportFinished(@Nullable String path) {
              importingProjects.remove(project);
              LOG.info("External system import finished, re-checking source roots");
              AppExecutorUtil.getAppExecutorService().execute(() -> {
                if (!project.isDisposed()) {
                  ensureSourceRoots(project, projectPath);
                }
              });
            }

            @Override
            public void onImportFailed(@Nullable String path) {
              importingProjects.remove(project);
              LOG.info("External system import failed, re-checking source roots");
              AppExecutorUtil.getAppExecutorService().execute(() -> {
                if (!project.isDisposed()) {
                  ensureSourceRoots(project, projectPath);
                }
              });
            }

            @Override
            public void onImportFailed(@Nullable String path, @NotNull Throwable t) {
              importingProjects.remove(project);
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

    registerGradleSyncGate(project);

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
   * Also ensures test-data directories under content roots are source folders so that
   * stub-index-based searches (ClassInheritorsSearch, etc.) find classes in test fixtures.
   */
  private void ensureSourceRoots(@NotNull Project project, @NotNull LspPath projectPath) {
    var moduleManager = ModuleManager.getInstance(project);
    var modules = moduleManager.getModules();

    if (modules.length == 0) {
      var projectDir = VirtualFileManager.getInstance().findFileByUrl(projectPath.toLspUri());
      if (projectDir == null) projectDir = projectPath.refreshAndFindVirtualFile();
      if (projectDir == null) {
        LOG.warn("Cannot find virtual file for project path: " + projectPath);
        return;
      }
      LOG.warn("Setting up source roots for workspace folder: " + projectPath);
      final var dir = projectDir;
      ApplicationManager.getApplication().invokeLater(MiscUtil.asWriteAction(() -> {
        try {
          var module = moduleManager.newModule(
              Files.createTempDirectory("idealsp-lsp-").resolve("lsp-module.iml"),
              "JAVA_MODULE");
          var model = ModuleRootManager.getInstance(module).getModifiableModel();
          ContentEntry ce = model.addContentEntry(dir);
          ce.addSourceFolder(dir, false);
          model.commit();
          LOG.warn("Added source root: " + dir.getUrl());
        } catch (Exception e) {
          LOG.warn("Failed to set up source roots for workspace: " + projectPath, e);
        }
      }));
      return;
    }

    // Existing modules may have content roots but test-data dirs under them
    // may not be source folders. Add them so stub indexing covers test fixtures.
    ApplicationManager.getApplication().invokeLater(MiscUtil.asWriteAction(() -> {
      for (var module : modules) {
        var rootManager = ModuleRootManager.getInstance(module);
        for (ContentEntry entry : rootManager.getContentEntries()) {
          var contentRootFile = entry.getFile();
          if (contentRootFile == null) continue;
          var testDataFile = contentRootFile.findChild("test-data");
          if (testDataFile == null) continue;
          boolean alreadySource = false;
          for (var sf : entry.getSourceFolders()) {
            if (testDataFile.equals(sf.getFile())) {
              alreadySource = true;
              break;
            }
          }
          if (!alreadySource) {
            try {
              var model = rootManager.getModifiableModel();
              for (ContentEntry ce : model.getContentEntries()) {
                if (ce.getUrl().equals(entry.getUrl())) {
                  ce.addSourceFolder(testDataFile, true);
                  break;
                }
              }
              model.commit();
              LOG.warn("Added test-data source folder: " + testDataFile.getUrl());
            } catch (Exception e) {
              LOG.warn("Failed to add test-data source folder: " + testDataFile.getUrl(), e);
            }
          }
        }
      }
    }));
  }

  /**
   * Subscribes to the Gradle sync lifecycle to mark the project as importing for the WHOLE
   * sync (including the model-fetch phase, during which no write is pending yet). This closes
   * the gap left by {@code ApplicationImpl.isWriteActionPending()}, which only turns on once
   * the workspace-model update requests its write — long-running readers (daemon highlighting)
   * that start during model fetch collide with that write-upgrade and wedge the lock.
   *
   * <p>The Gradle plugin is a bundled plugin, not on the compile classpath, so the listener
   * is subscribed reflectively. If it is unavailable, the gate degrades to the write-pending
   * check.
   */
  @SuppressWarnings("unchecked")
  private void registerGradleSyncGate(@NotNull Project project) {
    try {
      Class<?> listenerClass = findGradleSyncListenerClass();
      var topic = (com.intellij.util.messages.Topic<Object>) listenerClass.getField("TOPIC").get(null);
      var handler = java.lang.reflect.Proxy.newProxyInstance(
          listenerClass.getClassLoader(),
          new Class<?>[]{listenerClass},
          (proxy, method, args) -> {
            switch (method.getName()) {
              case "onModelFetchPhaseCompleted":
              case "onModelFetchCompleted":
                importingProjects.add(project);
                LOG.warn("Gradle sync gate: model fetch in progress for " + project.getName());
                break;
              case "onProjectLoadedActionCompleted":
                importingProjects.remove(project);
                LOG.warn("Gradle sync gate: sync finished for " + project.getName());
                break;
              case "onModelFetchFailed":
                importingProjects.remove(project);
                LOG.warn("Gradle sync gate: model fetch failed for " + project.getName());
                break;
              case "toString":
                return "GradleSyncListener(idealsp sync gate)";
              case "hashCode":
                return System.identityHashCode(proxy);
              case "equals":
                return proxy == args[0];
              default:
                return null;
            }
            return null;
          });
      project.getMessageBus().connect().subscribe(topic, handler);
      LOG.warn("Gradle sync gate registered for " + project.getName());
    } catch (Exception e) {
      LOG.warn("Gradle plugin not available; sync gate relies on the write-pending check", e);
    }
  }

  /**
   * Locates the {@code GradleSyncListener} class via the bundled {@code com.intellij.gradle}
   * plugin's own classloader. A {@code <depends>} declaration is intentionally NOT used: it
   * breaks the light test framework's parameter-info extension-point registration.
   */
  private static @NotNull Class<?> findGradleSyncListenerClass() throws ClassNotFoundException {
    for (IdeaPluginDescriptor descriptor : PluginManagerCore.getPlugins()) {
      if ("com.intellij.gradle".equals(descriptor.getPluginId().getIdString())) {
        var loader = descriptor.getPluginClassLoader();
        if (loader != null) {
          return Class.forName("org.jetbrains.plugins.gradle.service.syncAction.GradleSyncListener", true, loader);
        }
      }
    }
    throw new ClassNotFoundException("com.intellij.gradle plugin is not loaded");
  }
}
