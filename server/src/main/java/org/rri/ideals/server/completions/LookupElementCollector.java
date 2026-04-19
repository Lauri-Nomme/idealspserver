package org.rri.ideals.server.completions;

import com.intellij.codeInsight.completion.CompletionResult;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.PrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElement;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple collector for completion results
 */
public class LookupElementCollector {
  private final List<LookupElement> lookupElements = new ArrayList<>();

  public void addResult(@NotNull CompletionResult result) {
    lookupElements.add(result.getLookupElement());
  }

  public void addLookupElement(@NotNull LookupElement lookupElement) {
    lookupElements.add(lookupElement);
  }

  @NotNull
  public List<LookupElement> getLookupElements() {
    return lookupElements;
  }
}