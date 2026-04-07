package org.rri.ideals.server.bootstrap;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;

public class StartLspServerAction extends AnAction {
  private static final Logger LOG = Logger.getInstance(StartLspServerAction.class);
  private static final int DEFAULT_PORT = 8989;

  private static TcpLspServerRunner runner;
  private static Thread serverThread;
  private static volatile boolean isRunning = false;
  private static boolean shouldStop = false;

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
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
    } catch (Exception e) {
      LOG.warn("Could not close server socket: " + e);
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    super.update(e);
    String title = isRunning ? "Stop LSP Server" : "Start LSP Server";
    e.getPresentation().setText(title);
  }
}
