package tf.locals.idealsp.server.rename;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.Segment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.rename.RenameProcessor;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.usageView.UsageInfo;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.commands.ExecutorContext;
import tf.locals.idealsp.server.commands.LspCommand;
import tf.locals.idealsp.server.util.EditorUtil;
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RenameCommand extends LspCommand<WorkspaceEdit> {
  private final Position pos;
  private final String newName;

  public RenameCommand(Position pos, String newName) {
    this.pos = pos;
    this.newName = newName;
  }

  @Override
  protected @NotNull Supplier<@NotNull String> getMessageSupplier() {
    return () -> "Rename call";
  }

  @Override
  protected boolean isCancellable() {
    return false;
  }

  @Override
  public @NotNull CompletableFuture<@Nullable WorkspaceEdit> runAsync(@NotNull com.intellij.openapi.project.Project project, @NotNull LspPath path) {
    // First, compute the WorkspaceEdit (read action via parent)
    var editFuture = super.runAsync(project, path);
    // Apply the rename server-side asynchronously so it doesn't block the LSP response.
    // The server-side apply is a side effect; the client receives the WorkspaceEdit immediately.
    editFuture.thenAcceptAsync(edit -> {
      if (edit == null) return;
      try {
        applyEdit(project, edit);
      } catch (Throwable t) {
        LOG.warn("RenameCommand: server-side apply failed", t);
      }
    }, ForkJoinPool.commonPool());
    return editFuture;
  }

  private void applyEdit(@NotNull com.intellij.openapi.project.Project project, @NotNull WorkspaceEdit edit) {
    LOG.warn("RenameCommand.applyEdit: applying rename server-side on thread " + Thread.currentThread().getName());
    ApplicationManager.getApplication().invokeAndWait(() -> {
      CommandProcessor.getInstance().executeCommand(project, () -> {
        WriteAction.run(() -> {
          var psiDocManager = PsiDocumentManager.getInstance(project);
          var fileDocManager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
          for (var editOrResource : edit.getDocumentChanges()) {
            if (editOrResource.isLeft()) {
              var textDocEdit = editOrResource.getLeft();
              var vFile = LspPath.fromLspUri(textDocEdit.getTextDocument().getUri()).findVirtualFile();
              if (vFile == null) continue;
              var psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vFile);
              if (psiFile == null) continue;
              var doc = MiscUtil.getDocument(psiFile);
              if (doc == null) continue;
              var edits = new java.util.ArrayList<>(textDocEdit.getEdits());
              // Apply edits in reverse order to preserve offsets
              edits.sort((a, b) -> {
                int lineDiff = Integer.compare(b.getRange().getStart().getLine(), a.getRange().getStart().getLine());
                if (lineDiff != 0) return lineDiff;
                return Integer.compare(b.getRange().getStart().getCharacter(), a.getRange().getStart().getCharacter());
              });
              for (var textEdit : edits) {
                int startOffset = MiscUtil.positionToOffset(doc, textEdit.getRange().getStart());
                int endOffset = MiscUtil.positionToOffset(doc, textEdit.getRange().getEnd());
                doc.replaceString(startOffset, endOffset, textEdit.getNewText());
              }
              psiDocManager.commitDocument(doc);
              fileDocManager.saveDocument(doc);
            }
          }
          LOG.warn("RenameCommand.applyEdit: rename applied successfully");
        });
      }, "Rename", null);
    }, ModalityState.nonModal());
  }

  @Override
  protected @Nullable WorkspaceEdit execute(@NotNull ExecutorContext ctx) {
    LOG.warn("RenameCommand.execute: start");
    final var file = ctx.getPsiFile();
    Document doc = MiscUtil.getDocument(file);
    if (doc == null) {
      return null;
    }
    var elementRef = new Ref<PsiElement>();
    final var disposable = Disposer.newDisposable();
    try {
      EditorUtil.withEditor(disposable, file, pos, editor -> {
        int offset = editor.getCaretModel().getOffset();
        var elementToRename = EditorUtil.findTargetElement(editor, offset);
        if (elementToRename != null) {
          final var processor = RenamePsiElementProcessor.forElement(elementToRename);
          final var newElementToRename = processor.substituteElementToRename(elementToRename, editor);
          if (newElementToRename != null) {
            elementToRename = newElementToRename;
          }
        }
        elementRef.set(elementToRename);
      });
    } finally {
      Disposer.dispose(disposable);
    }

    if (elementRef.get() == null) {
      LOG.warn("RenameCommand.execute: element not found");
      return null;
    }

    LOG.warn("RenameCommand.execute: found element, creating RenameProcessor");
    final var elemToName = new LinkedHashMap<PsiElement, String>();
    elemToName.put(elementRef.get(), newName);
    final var renamer = new RenameProcessor(ctx.getProject(), elementRef.get(), newName, false, false);
    renamer.prepareRenaming(elementRef.get(), newName, elemToName);
    elemToName.forEach(renamer::addElement);

    LOG.warn("RenameCommand.execute: calling findUsages");
    final UsageInfo[] usages = renamer.findUsages();
    LOG.warn("RenameCommand.execute: findUsages returned " + usages.length + " usages");
    final var usageEdits = Arrays.stream(usages)
        .filter(usage -> !usage.isNonCodeUsage)
        .map(usageInfo -> new Pair<>(usageInfoToLocation(usageInfo), newName));

    final var targetEdits = Arrays.stream(elemToName.keySet().toArray(new PsiElement[0]))
        .map(elem -> new Pair<>(MiscUtil.psiElementToLocation(elem), elemToName.get(elem)));

    final var checkSet = new HashSet<Location>();
    final var textDocumentEdits = Stream.concat(targetEdits, usageEdits)
        .filter(pair -> {
          final var loc = pair.getFirst();
          return loc != null && checkSet.add(loc);
        })
        .collect(Collectors.groupingBy(
                pair -> pair.getFirst().getUri(),
                Collectors.mapping(pair -> new Pair<>(pair.getFirst().getRange(), pair.getSecond()), Collectors.toList())
        ))
        .entrySet().stream()
        .map(this::convertEntry)
        .toList();

    return new WorkspaceEdit(textDocumentEdits);
  }

  private static final Logger LOG = Logger.getInstance(RenameCommand.class);

  private static @Nullable Location usageInfoToLocation(@NotNull UsageInfo info) {
    final var psiFile = info.getFile();
    final var segment = info.getSegment();
    if (psiFile == null || segment == null) {
      return null;
    }
    final var uri = LspPath.fromVirtualFile(psiFile.getVirtualFile()).toLspUri();
    final var doc = MiscUtil.getDocument(psiFile);
    if (doc == null) {
      return null;
    }
    return new Location(uri, segmentToRange(doc, segment));
  }

  private static @NotNull Range segmentToRange(@NotNull Document doc, @NotNull Segment segment) {
    return new Range(MiscUtil.offsetToPosition(doc, segment.getStartOffset()),
        MiscUtil.offsetToPosition(doc, segment.getEndOffset()));
  }

  private @NotNull Either<@NotNull TextDocumentEdit, @NotNull ResourceOperation> convertEntry(
      @NotNull Map.Entry<@NotNull String, @NotNull List<@NotNull Pair<@NotNull Range, @NotNull String>>> entry) {
    return Either.forLeft(
        new TextDocumentEdit(
            new VersionedTextDocumentIdentifier(entry.getKey(), 1),
            entry.getValue().stream().map(pair -> new TextEdit(pair.getFirst(), pair.getSecond())).toList()
        ));
  }
}
