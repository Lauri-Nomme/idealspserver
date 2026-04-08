package org.rri.ideals.server.bootstrap;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class StartLspServerAction extends AnAction {
  private static final Logger LOG = Logger.getInstance(StartLspServerAction.class);
  private static final int DEFAULT_PORT = 8989;

  private static TcpLspServerRunner runner;
  private static Thread serverThread;
  private static volatile boolean isRunning = false;
  private static boolean shouldStop = false;
  private static Project lastProject = null;

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    lastProject = e.getProject();
    if (isRunning) {
      stopServer();
    } else {
      startServer(DEFAULT_PORT);
    }
  }

  private void startServer(int port) {
    if (isRunning) {
      LOG.info("LSP Server is already running on port " + port);
      return;
    }

    shouldStop = false;
    runner = new TcpLspServerRunner();
    runner.setPort(port);

    serverThread = new Thread(() -> {
      try {
        LOG.info("Starting LSP Server on port " + port);
        isRunning = true;
        runner.launch().get();
      } catch (Exception ex) {
        if (!shouldStop) {
          LOG.error("LSP Server error: " + ex);
        }
      } finally {
        isRunning = false;
        LOG.info("LSP Server stopped");
      }
    }, "LSP-Server-Thread");

    serverThread.start();
    LOG.info("LSP Server started on port " + port);
    showNotification("LSP Server started on port " + port);
  }

  private void stopServer() {
    if (!isRunning || runner == null) {
      LOG.info("LSP Server is not running");
      return;
    }

    LOG.info("Stopping LSP Server");
    shouldStop = true;
    try {
      runner.closeServerSocketInternal();
      showNotification("LSP Server stopped");
    } catch (Exception e) {
      LOG.warn("Could not close server socket: " + e);
    }
  }

  private void showNotification(String message) {
    if (lastProject == null) return;
    try {
      var notificationClass = Class.forName("com.intellij.notification.Notification");
      var notificationTypeClass = Class.forName("com.intellij.notification.NotificationType");
      var infoType = notificationTypeClass.getField("INFORMATION").get(null);
      
      var constructor = notificationClass.getConstructor(String.class, String.class, String.class, notificationTypeClass);
      var notification = constructor.newInstance("IdeaLS", "IdeaLS", message, infoType);
      
      var notifyMethod = notificationClass.getMethod("notify", Project.class);
      notifyMethod.invoke(notification, lastProject);
    } catch (Exception e) {
      LOG.warn("Could not show notification: " + e.getMessage());
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    super.update(e);
    String title = isRunning ? "Stop LSP Server" : "Start LSP Server";
    e.getPresentation().setText(title);
  }
}
