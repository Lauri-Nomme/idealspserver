package tf.locals.idealsp.server.formatting;

import org.eclipse.lsp4j.FormattingOptions;
import org.jetbrains.annotations.NotNull;
import tf.locals.idealsp.server.util.MiscUtil;

public final class FormattingTestUtil {
  private FormattingTestUtil() {
  }

  @NotNull
  public static FormattingOptions defaultOptions() {
    return MiscUtil.with(
        new FormattingOptions(),
        formattingOptions -> {
          formattingOptions.setInsertFinalNewline(true);
          formattingOptions.setInsertSpaces(true);
          formattingOptions.setTabSize(4);
        });
  }

}
