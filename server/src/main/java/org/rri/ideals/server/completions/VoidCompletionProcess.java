package org.rri.ideals.server.completions;

import com.intellij.codeInsight.completion.CompletionProcess;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.progress.util.AbstractProgressIndicatorExBase;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.ObjectUtils;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

public class VoidCompletionProcess extends AbstractProgressIndicatorExBase implements Disposable, CompletionProcess {
  @Override
  public boolean isAutopopupCompletion() {
    return false;
  }

  @NotNull
  private final Object myLock = ObjectUtils.sentinel("VoidCompletionProcess");

  @Override
  public void dispose() {
  }

  void registerChildDisposable(@NotNull Supplier<Disposable> child) {
    synchronized (myLock) {
      checkCanceled();
      var childDisposable = child.get();
      if (childDisposable != null) {
        Disposer.register(this, childDisposable);
      }
    }
  }
}