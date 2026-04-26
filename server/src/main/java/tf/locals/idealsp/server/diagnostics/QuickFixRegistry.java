package tf.locals.idealsp.server.diagnostics;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.diagnostic.Logger;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

class QuickFixRegistry {
  private static final Logger LOG = Logger.getInstance(QuickFixRegistry.class);
  private static final Comparator<Position> POSITION_COMPARATOR =
      Comparator.comparingInt(Position::getLine).thenComparingInt(Position::getCharacter);

  private final ConcurrentHashMap<Anchor, List<HighlightInfo.IntentionActionDescriptor>> quickFixes = new ConcurrentHashMap<>();

  @NotNull
  public List<HighlightInfo.IntentionActionDescriptor> collectForRange(@NotNull Range range) {
    return quickFixes.values().stream()
        .flatMap(List::stream)
        .collect(Collectors.toList());
  }

  public void registerQuickFixes(@NotNull Range range, @NotNull List<HighlightInfo.IntentionActionDescriptor> actions) {
    quickFixes.put(new Anchor(range), actions);
  }

  private record Anchor(@NotNull Range range) {
  }
}
