package org.rri.ideals.server.references;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.fileEditor.FileEditorComposite;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager;
import com.intellij.openapi.fileEditor.ex.FileEditorWithProvider;
import com.intellij.openapi.fileEditor.impl.EditorComposite;
import com.intellij.openapi.fileEditor.impl.EditorFileSwapper;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rri.ideals.server.LspPath;
import org.rri.ideals.server.commands.ExecutorContext;
import org.rri.ideals.server.commands.LspCommand;
import org.rri.ideals.server.util.EditorUtil;
import org.rri.ideals.server.util.MiscUtil;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

abstract class FindDefinitionCommandBase extends LspCommand<Either<List<? extends Location>, List<? extends LocationLink>>> {
  private static final Logger LOG = Logger.getInstance(FindDefinitionCommandBase.class);
  private static final ExtensionPointName<EditorFileSwapper> EDITOR_FILE_SWAPPER_EP_NAME =
      new ExtensionPointName<>("com.intellij.editorFileSwapper");

  @NotNull
  protected final Position pos;

  protected FindDefinitionCommandBase(@NotNull Position pos) {
    this.pos = pos;
  }

  @Override
  protected boolean isCancellable() {
    return false;
  }

  @Override
  protected @NotNull Either<List<? extends Location>, @NotNull List<? extends LocationLink>> execute(@NotNull ExecutorContext ctx) {
    PsiFile file = ctx.getPsiFile();
    Document doc = MiscUtil.getDocument(file);
    if (doc == null) {
      return Either.forRight(List.of());
    }

    var offset = MiscUtil.positionToOffset(doc, pos);
    PsiElement originalElem = file.findElementAt(offset);
    Range originalRange = MiscUtil.getPsiElementRange(doc, originalElem);

    if (originalElem == null) {
      return Either.forRight(List.of());
    }

    // Use EditorUtil to create an editor so we can call findDefinitions() properly
    var definitionsRef = new Ref<List<? extends LocationLink>>();
    var disposable = Disposer.newDisposable();
    try {
      EditorUtil.withEditor(disposable, file, pos, editor -> {
        // Now actually call the subclass's findDefinitions() method - this was the bug!
        // Previously we just used originalElem as targetElement and never called findDefinitions()
        var targetElements = findDefinitions(editor, offset).collect(Collectors.toList());

        var definitions = targetElements.stream()
            .filter(targetElem -> targetElem != null && targetElem.getContainingFile() != null)
            .map(targetElem -> {
              final var loc = findSourceLocation(ctx.getProject(), targetElem);
              if (loc != null) {
                return new LocationLink(loc.getUri(), loc.getRange(), loc.getRange(), originalRange);
              } else {
                Document targetDoc = targetElem.getContainingFile().equals(file)
                    ? doc : MiscUtil.getDocument(targetElem.getContainingFile());
                return MiscUtil.psiElementToLocationLink(targetElem, targetDoc, originalRange);
              }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        definitionsRef.set((List) definitions);
      });
    } finally {
      Disposer.dispose(disposable);
    }

    return Either.forRight(definitionsRef.get() != null ? definitionsRef.get() : List.of());
  }

  /**
   * Tries to find the corresponding source file location for this element.
   * <p>
   * Depends on the element contained in a library's class file and the corresponding sources jar/zip attached
   * to the library.
   */
  @Nullable
  private static Location findSourceLocation(@NotNull Project project, @NotNull PsiElement element) {
    final var file = element.getContainingFile();
    final var doc = MiscUtil.getDocument(file);
    if (doc == null) {
      return null;
    }

    final var location = MiscUtil.psiElementToLocation(element, file);
    if (location == null) {
      return null;
    }

    final var editor = newEditorComposite(project, file.getVirtualFile());
    if (editor == null) {
      return location;
    }

    final var psiAwareEditor = EditorFileSwapper.findSinglePsiAwareEditor(editor.getAllEditors());
    if (psiAwareEditor == null) {
      return location;
    }
    psiAwareEditor.getEditor().getCaretModel().moveToOffset(MiscUtil.positionToOffset(doc, location.getRange().getStart()));

    final var newFilePair = EDITOR_FILE_SWAPPER_EP_NAME.getExtensionList().stream()
            .map(fileSwapper -> fileSwapper.getFileToSwapTo(project, editor))
            .filter(Objects::nonNull)
            .findFirst();

    if (newFilePair.isEmpty() || newFilePair.get().getFirst() == null) {
      return location;
    }

    final var sourcePsiFile = MiscUtil.resolvePsiFile(project,
        LspPath.fromVirtualFile(newFilePair.get().getFirst()));
    if (sourcePsiFile == null) {
      return location;
    }
    final var sourceDoc = MiscUtil.getDocument(sourcePsiFile);
    if (sourceDoc == null) {
      return location;
    }
    final var virtualFile = newFilePair.get().getFirst();
    final var offset = newFilePair.get().getFirst() != null ? newFilePair.get().getSecond() : 0;
    assert virtualFile != null;
    return new Location(LspPath.fromVirtualFile(virtualFile).toLspUri(),
            new Range(MiscUtil.offsetToPosition(sourceDoc, offset), MiscUtil.offsetToPosition(sourceDoc, offset)));
  }

  @Nullable
  private static EditorComposite newEditorComposite(@NotNull final Project project, @Nullable final VirtualFile file) {
    if (file == null) {
      return null;
    }
    final var application = ApplicationManager.getApplication();
    if (application == null) {
      return null;
    }
    final var fileManager = FileEditorManager.getInstance(project);
    if (fileManager == null) {
      return null;
    }

    final var resultHolder = new java.util.concurrent.atomic.AtomicReference<EditorComposite>();
    try {
      application.invokeAndWait(() -> {
        final var composite = fileManager.getComposite(file);
        if (composite instanceof EditorComposite) {
          resultHolder.set((EditorComposite) composite);
        }
      });
    } catch (Exception e) {
      LOG.warn("Could not create EditorComposite: " + e);
      return null;
    }
    return resultHolder.get();
  }


  @NotNull
  protected abstract Stream<PsiElement> findDefinitions(@NotNull Editor editor, int offset);
}
