package tf.locals.idealsp.server;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ProjectSessionRegistry {
    private static final Logger LOG = Logger.getInstance(ProjectSessionRegistry.class);
    private static volatile ProjectSessionRegistry instance;

    private final Map<LspPath, ProjectSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "idealsp-session-ttl");
        t.setDaemon(true);
        return t;
    });

    private static final int TTL_HOURS = Integer.getInteger("idealsp.lsp.session.ttl.hours", 2);

    private ProjectSessionRegistry() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "idealsp-session-shutdown"));
    }

    @NotNull
    public static ProjectSessionRegistry getInstance() {
        if (instance == null) {
            synchronized (ProjectSessionRegistry.class) {
                if (instance == null) {
                    instance = new ProjectSessionRegistry();
                }
            }
        }
        return instance;
    }

    @NotNull
    public Project openOrClaimProject(@NotNull LspPath workspaceRoot) {
        ProjectSession session = sessions.compute(workspaceRoot, (root, existing) -> {
            if (existing != null && !existing.project.isDisposed()) {
                existing.refcount.incrementAndGet();
                existing.cancelTtl();
                LOG.info("Reusing existing project for " + root + " (refcount=" + existing.refcount.get() + ")");
                return existing;
            }
            Project project = ProjectService.getInstance().resolveProjectFromRoot(root);
            LOG.info("Opened new project for " + root + " (refcount=1)");
            return new ProjectSession(project);
        });
        return session.project;
    }

    public void releaseProject(@NotNull LspPath workspaceRoot) {
        sessions.computeIfPresent(workspaceRoot, (root, session) -> {
            int count = session.refcount.decrementAndGet();
            LOG.info("Client disconnected from " + root + " (refcount=" + count + ")");
            if (count == 0) {
                LOG.info("All clients disconnected from " + root + ". Keeping alive for " + TTL_HOURS + "h");
                session.scheduleTtl(() -> {
                    sessions.remove(root);
                    if (!session.project.isDisposed()) {
                        LOG.info("TTL expired (" + TTL_HOURS + "h). Closing project: " + root);
                        ProjectService.getInstance().closeProject(session.project);
                    }
                });
            }
            return session;
        });
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    private void shutdown() {
        LOG.info("Shutting down session registry (" + sessions.size() + " active sessions)");
        scheduler.shutdown();
        for (var entry : sessions.entrySet()) {
            Project project = entry.getValue().project;
            if (!project.isDisposed()) {
                ProjectService.getInstance().closeProject(project);
            }
        }
        sessions.clear();
    }

    private class ProjectSession {
        final Project project;
        final AtomicInteger refcount = new AtomicInteger(1);
        @Nullable
        ScheduledFuture<?> ttlFuture;

        ProjectSession(@NotNull Project project) {
            this.project = project;
        }

        void scheduleTtl(@NotNull Runnable onExpiry) {
            cancelTtl();
            ttlFuture = scheduler.schedule(onExpiry, TTL_HOURS, TimeUnit.HOURS);
        }

        void cancelTtl() {
            if (ttlFuture != null && !ttlFuture.isDone()) {
                ttlFuture.cancel(false);
                ttlFuture = null;
            }
        }
    }
}
