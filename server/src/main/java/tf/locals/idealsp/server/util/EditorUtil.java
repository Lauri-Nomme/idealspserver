package tf.locals.idealsp.server.util;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Function;

public class EditorUtil {
  private static final Logger LOG = Logger.getInstance(EditorUtil.class);

  private EditorUtil() {
  }

  @NotNull
  public static Editor createEditor(@NotNull Disposable context,
                                    @NotNull PsiFile file,
                                    @NotNull Position position) {
    Document doc = MiscUtil.getDocument(file);
    EditorFactory editorFactory = EditorFactory.getInstance();

    assert doc != null;
    Editor created;
    try {
      // Use invokeAndWait outside write action to avoid IntelliJ 2026.1 modal progress issue
      Ref<Editor> editorRef = new Ref<>();
      ApplicationManager.getApplication().invokeAndWait(() -> {
        editorRef.set(editorFactory.createEditor(doc, file.getProject()));
      });
      created = editorRef.get();
    } catch (Exception e) {
      LOG.warn("editorFactory.createEditor threw " + e.getClass().getName() 
          + " for file=" + file.getName() + " - releasing any leaked editor");
      // If EditorFactory.createEditor threw (e.g., due to ProcessCanceledException
      // during EditorImpl construction), the editor may be registered with EditorFactory
      // but not returned. We need to find and release it.
      for (Editor editor : editorFactory.getAllEditors()) {
        if (editor.getDocument() == doc && !editor.isDisposed()) {
          editorFactory.releaseEditor(editor);
        }
      }
      throw e;
    }
    final int line = position.getLine();
    final int character = position.getCharacter();
    ApplicationManager.getApplication().invokeAndWait(() -> {
      created.getCaretModel().moveToLogicalPosition(new LogicalPosition(line, character));
    });

    Disposer.register(context, () -> {
      if (!created.isDisposed()) {
        editorFactory.releaseEditor(created);
      }
    });

    return created;
  }


  public static void withEditor(@NotNull Disposable context,
                                @NotNull PsiFile file,
                                @NotNull Position position,
                                @NotNull Consumer<Editor> callback) {
    computeWithEditor(context, file, position, editor -> {
      callback.accept(editor);
      return null;
    });
  }

  public static <T> T computeWithEditor(@NotNull Disposable context,
                                        @NotNull PsiFile file,
                                        @NotNull Position position,
                                        @NotNull Function<Editor, T> callback) {
    Editor editor = createEditor(context, file, position);

    try {
      return callback.apply(editor);
    } catch (Exception e) {
      throw MiscUtil.wrap(e);
    }
  }

  public static @Nullable PsiElement findTargetElement(@NotNull Editor editor) {
    return TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().getAllAccepted());
  }

  public static @Nullable PsiElement findTargetElement(@NotNull Editor editor, int offset) {
    return TargetElementUtil.getInstance().findTargetElement(editor, TargetElementUtil.getInstance().getAllAccepted(), offset);
  }
}
