package org.rri.ideals.server.completions;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupArranger;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil;
import com.intellij.reference.SoftReference;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.intellij.openapi.client.ClientProjectSession;
import com.intellij.openapi.client.ClientSessionsUtil;

import java.util.ArrayList;
import java.util.List;

public class CompletionInfo {
  private static final Logger LOG = Logger.getInstance(CompletionInfo.class);

  @NotNull
  private static final Key<SoftReference<Pair<PsiFile, Document>>> FILE_COPY_KEY = Key.create("CompletionFileCopy");
  @NotNull
  private final CompletionInitializationContext initContext;
  @NotNull
  private final CompletionParameters parameters;
  @NotNull
  private final LookupArrangerImpl arranger;
  @NotNull
  private final LookupImpl lookup;
  @NotNull
  private final VoidCompletionProcess process;

  @SuppressWarnings("UnstableApiUsage")
  public CompletionInfo(@NotNull Editor editor, @NotNull Project project) {
    process = new VoidCompletionProcess();
    initContext = CompletionInitializationUtil.createCompletionInitializationContext(
        project,
        editor,
        editor.getCaretModel().getPrimaryCaret(),
        1,
        CompletionType.BASIC);
    assert initContext != null;

    var file = initContext.getFile();
    var offsetMap = initContext.getOffsetMap();
    parameters = CompletionInitializationUtil.createCompletionParameters(
        initContext,
        process,
        new OffsetsInFile(file, offsetMap));
    arranger = new LookupArrangerImpl(parameters);
    ClientProjectSession session = ClientSessionsUtil.getCurrentSession(project);
    lookup = new LookupImpl(session, editor, arranger);
  }

  @NotNull
  public VoidCompletionProcess getProcess() {
    return process;
  }
  @NotNull
  public CompletionInitializationContext getInitContext() {
    return initContext;
  }
  @NotNull
  public CompletionParameters getParameters() {
    return parameters;
  }
  @NotNull
  public LookupArrangerImpl getArranger() {
    return arranger;
  }

  static class LookupArrangerImpl extends LookupArranger {
    @NotNull
    private final CompletionParameters parameters;

    @NotNull
    private final ArrayList<LookupElementWithMatcher> itemsWithPrefix = new ArrayList<>();

    LookupArrangerImpl(@NotNull CompletionParameters parameters) {
      this.parameters = parameters;
    }

    /* todo
    Add completion results sorting
    */
    void addElement(@NotNull CompletionResult completionItem) {
      var presentation = new LookupElementPresentation();
      ReadAction.run(() -> completionItem.getLookupElement().renderElement(presentation));
      registerMatcher(completionItem.getLookupElement(), completionItem.getPrefixMatcher());
      itemsWithPrefix.add(new LookupElementWithMatcher(completionItem.getLookupElement(),
          completionItem.getPrefixMatcher()));
      super.addElement(completionItem.getLookupElement(), presentation);
    }

    @Override
    @NotNull
    public Pair<List<LookupElement>, Integer> arrangeItems(@NotNull Lookup lookup, boolean onExplicitAction) {
      var toSelect = 0;
      return new Pair<>(itemsWithPrefix.stream().map(LookupElementWithMatcher::lookupElement).toList(), toSelect);
    }

    @NotNull ArrayList<LookupElementWithMatcher> getElementsWithMatcher() {
      return itemsWithPrefix;
    }

    @Override
    @NotNull
    public LookupArranger createEmptyCopy() {
      return new LookupArrangerImpl(parameters);
    }
  }

  @NotNull
  public LookupImpl getLookup() {
    return lookup;
  }

  @NotNull
  private OffsetsInFile insertDummyIdentifier(
      @NotNull CompletionInitializationContext initContext,
      @NotNull VoidCompletionProcess indicator,
      @NotNull OffsetsInFile topLevelOffsets) {
    return topLevelOffsets;
  }
  @NotNull
  private static PsiFile obtainFileCopy(@NotNull PsiFile file,
                                        boolean forbidCaching) {
    final VirtualFile virtualFile = file.getVirtualFile();
    boolean mayCacheCopy = !forbidCaching && file.isPhysical() &&
        // Idea developer: "we don't want to cache code fragment copies even if they appear to be physical"
        virtualFile != null && virtualFile.isInLocalFileSystem();
    if (mayCacheCopy) {
      final Pair<PsiFile, Document> cached = SoftReference.dereference(file.getUserData(FILE_COPY_KEY));
      if (cached != null && isCopyUpToDate(cached.second, cached.first, file)) {
        PsiFile copy = cached.first;
        assertCorrectOriginalFile("Cached", file, copy);
        return copy;
      }
    }

    final PsiFile copy = (PsiFile) file.copy();
    if (copy.isPhysical() || copy.getViewProvider().isEventSystemEnabled()) {
      LOG.error("File copy should be non-physical and non-event-system-enabled! Language=" +
          file.getLanguage() +
          "; file=" +
          file +
          " of " +
          file.getClass());
    }
    assertCorrectOriginalFile("New", file, copy);

    if (mayCacheCopy) {
      final Document document = copy.getViewProvider().getDocument();
      assert document != null;
      syncAcceptSlashR(file.getViewProvider().getDocument(), document);
      file.putUserData(FILE_COPY_KEY, new SoftReference<>(Pair.create(copy, document)));
    }
    return copy;
  }
  private static void syncAcceptSlashR(@Nullable Document originalDocument, @NotNull Document documentCopy) {
    if (!(originalDocument instanceof DocumentImpl) || !(documentCopy instanceof DocumentImpl)) {
      return;
    }

    ((DocumentImpl) documentCopy).setAcceptSlashR(((DocumentImpl) originalDocument).acceptsSlashR());
  }

  private static boolean isCopyUpToDate(Document document,
                                        @NotNull PsiFile copyFile,
                                        @NotNull PsiFile originalFile) {
    if (!copyFile.getClass().equals(originalFile.getClass()) ||
        !copyFile.isValid() ||
        !copyFile.getName().equals(originalFile.getName())) {
      return false;
    }
    /*
     Idea developers:
     the psi file cache might have been cleared by some external activity,
     in which case PSI-document sync may stop working
     */
    PsiFile current = PsiDocumentManager.getInstance(copyFile.getProject()).getPsiFile(document);
    return current != null && current.getViewProvider().getPsi(copyFile.getLanguage()) == copyFile;
  }

  @NotNull
  private static @NonNls String fileInfo(@NotNull PsiFile file) {
    return file + " of " + file.getClass() +
        " in " + file.getViewProvider() + ", languages=" + file.getViewProvider().getLanguages() +
        ", physical=" + file.isPhysical();
  }

  // this assertion method is copied from package-private method in CompletionAssertions class
  private static void assertCorrectOriginalFile(@NonNls String prefix,
                                                @NotNull PsiFile file,
                                                @NotNull PsiFile copy) {
    if (copy.getOriginalFile() != file) {
      throw new AssertionError(prefix + " copied file doesn't have correct original: noOriginal=" + (copy.getOriginalFile() == copy) +
          "\n file " + fileInfo(file) +
          "\n copy " + fileInfo(copy));
    }
  }
}
