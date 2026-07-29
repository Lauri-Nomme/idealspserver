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
import com.intellij.util.ui.EDT;
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
    return createEditor(context, file, position, null, null);
  }

  @NotNull
  public static Editor createEditor(@NotNull Disposable context,
                                    @NotNull PsiFile file,
                                    @NotNull Position position,
                                    @Nullable Position selectionStart,
                                    @Nullable Position selectionEnd) {
    Document doc = MiscUtil.getDocument(file);
    EditorFactory editorFactory = EditorFactory.getInstance();

    assert doc != null;
    Editor created;
    try {
      if (EDT.isCurrentThreadEdt()) {
        created = editorFactory.createEditor(doc, file.getProject());
      } else {
        Ref<Editor> editorRef = new Ref<>();
        ApplicationManager.getApplication().invokeAndWait(() -> {
          editorRef.set(editorFactory.createEditor(doc, file.getProject()));
        });
        created = editorRef.get();
      }
    } catch (Exception e) {
      LOG.warn("editorFactory.createEditor threw " + e.getClass().getName() 
          + " for file=" + file.getName() + " - releasing any leaked editor");
      for (Editor editor : editorFactory.getAllEditors()) {
        if (editor.getDocument() == doc && !editor.isDisposed()) {
          editorFactory.releaseEditor(editor);
        }
      }
      throw e;
    }
    final int line = position.getLine();
    final int character = position.getCharacter();
    if (EDT.isCurrentThreadEdt()) {
      created.getCaretModel().moveToLogicalPosition(new LogicalPosition(line, character));
    } else {
      ApplicationManager.getApplication().invokeAndWait(() -> {
        created.getCaretModel().moveToLogicalPosition(new LogicalPosition(line, character));
      });
    }

    // If a range is provided, set the selection
    if (selectionStart != null && selectionEnd != null) {
      int startOffset = created.logicalPositionToOffset(new LogicalPosition(selectionStart.getLine(), selectionStart.getCharacter()));
      int endOffset = created.logicalPositionToOffset(new LogicalPosition(selectionEnd.getLine(), selectionEnd.getCharacter()));
      final int finalStart = startOffset;
      final int finalEnd = endOffset;
      if (EDT.isCurrentThreadEdt()) {
        created.getSelectionModel().setSelection(finalStart, finalEnd);
      } else {
        ApplicationManager.getApplication().invokeAndWait(() -> {
          created.getSelectionModel().setSelection(finalStart, finalEnd);
        });
      }
    }

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
    computeWithEditor(context, file, position, null, null, editor -> {
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

  public static void withEditor(@NotNull Disposable context,
                                @NotNull PsiFile file,
                                @NotNull Position position,
                                @Nullable Position selectionStart,
                                @Nullable Position selectionEnd,
                                @NotNull Consumer<Editor> callback) {
    computeWithEditor(context, file, position, selectionStart, selectionEnd, editor -> {
      callback.accept(editor);
      return null;
    });
  }

  public static <T> T computeWithEditor(@NotNull Disposable context,
                                        @NotNull PsiFile file,
                                        @NotNull Position position,
                                        @Nullable Position selectionStart,
                                        @Nullable Position selectionEnd,
                                        @NotNull Function<Editor, T> callback) {
    Editor editor = createEditor(context, file, position, selectionStart, selectionEnd);

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
