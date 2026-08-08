package tf.locals.idealsp.server.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.stubs.StubUpdatingIndex;
import com.intellij.psi.impl.cache.impl.id.IdIndex;
import com.intellij.util.indexing.FileBasedIndex;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tf.locals.idealsp.server.LspPath;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class MiscUtil {
  private static final Logger LOG = Logger.getInstance(MiscUtil.class);

  private MiscUtil() {
  }

  @NotNull
  public static <T> T with(@NotNull T object, @NotNull Consumer<T> block) {
    block.accept(object);
    return object;
  }

  @NotNull
  public static Position offsetToPosition(@NotNull Document doc, int offset) {
    if (offset == -1) {
      return new Position(0, 0);
    }
    var line = doc.getLineNumber(offset);
    var lineStartOffset = doc.getLineStartOffset(line);
    var column = offset - lineStartOffset;
    return new Position(line, column);
  }

  @Nullable
  public static PsiFile resolvePsiFile(@NotNull Project project, @NotNull LspPath path) {
    var result = new Ref<PsiFile>();
    invokeWithPsiFileInReadAction(project, path, result::set);
    return result.get();
  }

  @Nullable
  public static <T> T produceWithPsiFileInReadAction(@NotNull Project project,
                                                     @NotNull LspPath path,
                                                     @NotNull Function<@NotNull PsiFile, T> block) {
    final var virtualFile = path.findVirtualFile();

    if (virtualFile == null) {
      LOG.info("File not found: " + path);
      return null;
    }

    final var psiFile = ApplicationManager.getApplication().runReadAction((Computable<PsiFile>) () -> 
        PsiManager.getInstance(project).findFile(virtualFile));

    if (psiFile == null) {
      LOG.info("Unable to get PSI for virtual file: " + virtualFile);
      return null;
    }

    return ApplicationManager.getApplication().runReadAction((Computable<T>) () -> block.apply(psiFile));
  }

  public static void invokeWithPsiFileInReadAction(@NotNull Project project, @NotNull LspPath path, @NotNull Consumer<@NotNull PsiFile> block) {
    produceWithPsiFileInReadAction(project, path,
        (psiFile) -> {
          block.accept(psiFile);
          return null;
        });
  }

  @Nullable
  public static Document getDocument(@NotNull PsiFile file) {
    var virtualFile = file.getVirtualFile();

    if (virtualFile == null)
      return file.getViewProvider().getDocument();

    var doc = FileDocumentManager.getInstance().getDocument(virtualFile);

    if (doc == null) {
      FileDocumentManagerImpl.registerDocument(
          new DocumentImpl(file.getViewProvider().getContents()),
          virtualFile);
      doc = FileDocumentManager.getInstance()
          .getDocument(virtualFile);
    }

    return doc;
  }

  @NotNull
  public static Runnable asWriteAction(@NotNull Runnable action) {
    return () -> ApplicationManager.getApplication().runWriteAction(action);
  }

  @NotNull
  public static RuntimeException wrap(@NotNull Throwable e) {
    return e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
  }

  public interface RunnableWithException {
    void run() throws Exception;
  }

  public static Runnable asRunnable(@NotNull MiscUtil.RunnableWithException action) {
    return () -> {
      try {
        action.run();
      } catch (Exception e) {
        throw wrap(e);
      }
    };
  }

  public static <T> T makeThrowsUnchecked(@NotNull Callable<T> block) {
    try {
      return block.call();
    } catch (Exception e) {
      throw wrap(e);
    }
  }

  @Nullable
  public static LocationLink psiElementToLocationLink(@NotNull PsiElement targetElem, @Nullable Document doc, @Nullable Range originalRange) {
    if (doc == null) {
      return null;
    }
    Range range = getPsiElementRange(doc, targetElem);
    String uri = LspPath.fromVirtualFile(targetElem.getContainingFile().getVirtualFile()).toLspUri();
    return range != null ? new LocationLink(uri, range, range, originalRange) : null;
  }

  @Nullable
  public static Location psiElementToLocation(@Nullable PsiElement elem) {
    if (elem == null) {
      return null;
    }
    var file = elem.getContainingFile();
    return psiElementToLocation(elem, file);
  }

  @Nullable
  public static Location psiElementToLocation(@Nullable PsiElement elem, @NotNull PsiFile file) {
    var doc = getDocument(file);
    if (doc == null) {
      return null;
    }
    var uri = LspPath.fromVirtualFile(file.getVirtualFile()).toLspUri();
    Range range = getPsiElementRange(doc, elem);
    return range != null ? new Location(uri, range) : null;
  }

  @Nullable
  public static Range getPsiElementRange(@NotNull Document doc, @Nullable PsiElement elem) {
    TextRange range = null;
    if (elem == null) {
      return null;
    }
    if (elem instanceof PsiNameIdentifierOwner) {
      PsiElement identifier = ((PsiNameIdentifierOwner) elem).getNameIdentifier();
      if (identifier != null) {
        range = identifier.getTextRange();
      }
    }
    if (range == null) {
      range = elem.getTextRange();
    }
    return range != null ? getRange(doc, range) : null;
  }

  @NotNull
  public static Range getRange(@NotNull Document doc, @NotNull Segment segment) {
    return new Range(offsetToPosition(doc, segment.getStartOffset()), offsetToPosition(doc, segment.getEndOffset()));
  }

  public static int positionToOffset(@NotNull Document doc, @NotNull Position pos) {
    return doc.getLineStartOffset(pos.getLine()) + pos.getCharacter();
  }

  @NotNull
  public static <T> Stream<T> streamOf(T @Nullable [] array) {
    return array != null ? Arrays.stream(array) : Stream.empty();
  }

  public interface ThrowingConsumer<T> {
    void accept(T t) throws Exception;
  }

  @NotNull
  public static <T> Consumer<T> toConsumer(@NotNull ThrowingConsumer<T> block) {
    return t -> {
      try {
        block.accept(t);
      } catch (Exception e) {
        throw wrap(e);
      }
    };
  }

  public interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  public static <T> Supplier<T> toSupplier(@NotNull ThrowingSupplier<T> block) {
    return () -> {
      try {
        return block.get();
      } catch (Exception e) {
        throw wrap(e);
      }
    };
  }

  public static <T> T uncheckExceptions(@NotNull ThrowingSupplier<T> block) {
    return toSupplier(block).get();
  }

  @SuppressWarnings("unchecked")
  public static <T> @NotNull CompletableFuture<T> failed(@NotNull String operation, @NotNull String detail) {
    return (CompletableFuture<T>) CompletableFuture.failedFuture(
        new RuntimeException("[" + operation + "] " + detail));
  }

  /**
   * Runs the given block with IntelliJ's "alternative resolve" mode enabled.
   * This forces PSI references to resolve even when the file-based/stub index is
   * not yet fully built (e.g. shortly after server start), which otherwise causes
   * find-usages / rename / type-definition searches to silently miss cross-file usages.
   */
  public static <T> T withAlternativeResolve(@NotNull Project project, @NotNull Supplier<T> block) {
    final var ref = new Ref<T>();
    DumbService.getInstance(project).withAlternativeResolveEnabled(() -> ref.set(block.get()));
    return ref.get();
  }

  public static void waitForSmartMode(@NotNull Project project) {
    waitForSmartMode(project, null);
  }

  public static void waitForSmartMode(@NotNull Project project, @Nullable CancelChecker cancelToken) {
    // Use the proper IntelliJ API: blocks on a CountDownLatch via SmartModeScheduler,
    // waits until neither scanning nor dumb mode is active (getCurrentMode() == 0),
    // and checks ProgressManager.checkCanceled() internally.
    var dumbService = DumbService.getInstance(project);
    var deadline = System.currentTimeMillis() + 600_000;
    var remaining = 600_000L;
    while (dumbService.isDumb()) {
      if (cancelToken != null) cancelToken.checkCanceled();
      if (remaining <= 0) break;
      var ok = dumbService.waitForSmartMode(Math.min(remaining, 30_000));
      if (ok) return;
      remaining = deadline - System.currentTimeMillis();
    }
    if (dumbService.isDumb()) {
      LOG.warn("Still in dumb mode after 600s wait, proceeding anyway");
    }
  }

  /**
   * Blocks until the file-based search index is up to date for the whole project.
   * Dumb mode only guarantees the stub index; the word/symbol indices used by
   * reference search, workspace symbols and class hierarchies are built lazily in
   * the background and may still be incomplete right after {@code indexFinished}.
   * This forces those indices to finish so project-wide searches return complete
   * results even on a freshly-started server.
   */
  public static void ensureIndexUpToDate(@NotNull Project project) {
    var scope = GlobalSearchScope.allScope(project);
    var deadline = System.currentTimeMillis() + 300_000;
    for (int attempt = 0; attempt < 10 && System.currentTimeMillis() < deadline; attempt++) {
      ApplicationManager.getApplication().runReadAction(() -> {
        FileBasedIndex.getInstance().ensureUpToDate(StubUpdatingIndex.INDEX_ID, project, scope);
        FileBasedIndex.getInstance().ensureUpToDate(IdIndex.NAME, project, scope);
      });
    }
  }

  /**
   * Probes whether the project-wide file-based search index is currently quiescent and
   * thus usable for index-backed searches (references, workspace symbols, hierarchies).
   * {@code ensureUpToDate} alone can return before the background index scan has been
   * scheduled, so this polls the real index state instead: it reports {@code true} only
   * when the platform is not currently indexing any file. Callers should require the
   * value to hold across several consecutive samples to avoid signaling readiness during
   * a brief gap between queued index tasks.
   */
  public static boolean isSearchIndexReady(@NotNull Project project) {
    if (DumbService.isDumb(project)) return false;
    return ReadAction.compute(() -> {
      try {
        return FileBasedIndex.getInstance().getFileBeingCurrentlyIndexed() == null;
      } catch (Exception e) {
        return false;
      }
    });
  }
}
