package tf.locals.idealsp.server.formatting;

import com.intellij.application.options.CodeStyle;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.TextEdit;
import org.jetbrains.annotations.NotNull;
import tf.locals.idealsp.server.commands.LspCommand;

import java.util.List;

public abstract class FormattingCommandBase extends LspCommand<List<? extends TextEdit>> {
  @NotNull
  private final FormattingOptions formattingOptions;

  protected FormattingCommandBase(@NotNull FormattingOptions formattingOptions) {
    this.formattingOptions = formattingOptions;
  }

  @NotNull
  private CodeStyleSettings getConfiguredSettings(@NotNull PsiFile copy) {
    var codeStyleSettings =
        CodeStyleSettingsManager.getInstance().cloneSettings(CodeStyle.getSettings(copy));
    var indentOptions = codeStyleSettings.getIndentOptionsByFile(copy);
    try {
      Class<?> pythonLanguageClass = Class.forName("com.jetbrains.python.PythonLanguage");
      Object pythonLanguage = pythonLanguageClass.getMethod("getInstance").invoke(null);
      if (copy.getLanguage().equals(pythonLanguage)) {
        Class<?> pyCodeStyleSettingsClass = Class.forName("com.jetbrains.python.formatter.PyCodeStyleSettings");
        Object settings = codeStyleSettings.getCustomSettings((Class) pyCodeStyleSettingsClass);
        pyCodeStyleSettingsClass.getField("BLANK_LINE_AT_FILE_END").set(settings, formattingOptions.isInsertFinalNewline());
      }
    } catch (Exception ignored) {
    }

    indentOptions.TAB_SIZE = formattingOptions.getTabSize();
    indentOptions.INDENT_SIZE = formattingOptions.getTabSize();
    indentOptions.USE_TAB_CHARACTER = !formattingOptions.isInsertSpaces();

    return codeStyleSettings;
  }

  protected void doWithTemporaryCodeStyleSettingsForFile(@NotNull PsiFile psiFile,
                                                         @NotNull Runnable action) {
    CodeStyle.doWithTemporarySettings(
        psiFile.getProject(),
        getConfiguredSettings(psiFile),
        action);
  }
}
