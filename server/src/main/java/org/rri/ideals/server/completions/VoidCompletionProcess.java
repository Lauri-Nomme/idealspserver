package org.rri.ideals.server.completions;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProcess;
import com.intellij.codeInsight.completion.CompletionProcessEx;
import com.intellij.codeInsight.completion.OffsetsInFile;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.util.AbstractProgressIndicatorExBase;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.patterns.ElementPattern;
import com.intellij.openapi.util.UserDataHolderEx;
import com.intellij.util.ObjectUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public class VoidCompletionProcess extends AbstractProgressIndicatorExBase implements Disposable, CompletionProcessEx {
  private final Object myLock = ObjectUtils.sentinel("VoidCompletionProcess");
  private @Nullable OffsetsInFile hostOffsets;

  public void setHostOffsets(@NotNull OffsetsInFile hostOffsets) {
    this.hostOffsets = hostOffsets;
  }

  @Override
  public boolean isAutopopupCompletion() {
    return false;
  }

  @Override
  public void dispose() {
  }

  @Override
  public @NotNull Project getProject() {
    throw new UnsupportedOperationException();
  }

  @Override
  public @NotNull Editor getEditor() {
    throw new UnsupportedOperationException();
  }

  @Override
  public @NotNull Caret getCaret() {
    throw new UnsupportedOperationException();
  }

  @Override
  public @NotNull com.intellij.codeInsight.completion.OffsetMap getOffsetMap() {
    throw new UnsupportedOperationException();
  }

  @Override
  public @NotNull OffsetsInFile getHostOffsets() {
    if (hostOffsets == null) {
      throw new IllegalStateException("hostOffsets not set - call setHostOffsets() first");
    }
    return hostOffsets;
  }

  @Override
  public @Nullable Lookup getLookup() {
    return null;
  }

  @Override
  public void registerChildDisposable(@NotNull Supplier<? extends Disposable> child) {
    synchronized (myLock) {
      checkCanceled();
      var childDisposable = child.get();
      if (childDisposable != null) {
        Disposer.register(this, childDisposable);
      }
    }
  }

  @Override
  public void itemSelected(@NotNull com.intellij.codeInsight.lookup.LookupElement element, char completionChar) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addAdvertisement(@NotNull String text, @Nullable javax.swing.Icon icon) {
  }

  @Override
  public @NotNull CompletionParameters getParameters() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setParameters(@NotNull CompletionParameters parameters) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void scheduleRestart() {
  }

  @Override
  public void prefixUpdated() {
  }

  @Override
  public void addWatchedPrefix(int prefixStart, @NotNull ElementPattern<String> prefixCondition) {
  }
}