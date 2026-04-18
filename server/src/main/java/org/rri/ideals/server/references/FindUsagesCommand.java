package org.rri.ideals.server.references;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.find.FindManager;
import com.intellij.find.findUsages.CustomUsageSearcher;
import com.intellij.find.findUsages.FindUsagesHandlerBase;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.find.findUsages.FindUsagesOptions;
import com.intellij.find.impl.FindManagerImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.SearchRequestCollector;
import com.intellij.psi.search.SearchSession;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;
import com.intellij.usages.UsageInfoToUsageConverter;
import com.intellij.usages.UsageSearcher;
import com.intellij.util.ArrayUtil;
import com.intellij.util.CommonProcessors;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rri.ideals.server.commands.ExecutorContext;
import org.rri.ideals.server.commands.LspCommand;
import org.rri.ideals.server.util.EditorUtil;
import org.rri.ideals.server.util.MiscUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class FindUsagesCommand extends LspCommand<List<? extends Location>> {
  private static final Logger LOG = Logger.getInstance(FindUsagesCommand.class);
  @NotNull
  private final Position pos;

  public FindUsagesCommand(@NotNull Position pos) {
    this.pos = pos;
  }

  @Override
  protected @NotNull Supplier<@NotNull String> getMessageSupplier() {
    return () -> "References (Find usages) call";
  }

  @Override
  protected boolean isCancellable() {
    return true;
  }

@Override
  protected @NotNull List<? extends Location> execute(@NotNull ExecutorContext ctx) {
    PsiFile file = ctx.getPsiFile();
    Document doc = MiscUtil.getDocument(file);
    if (doc == null) {
      LOG.warn("FindUsagesCommand.execute: document is null");
      return List.of();
    }

    var project = ctx.getProject();
    if (project == null) {
      LOG.warn("FindUsagesCommand.execute: project is null");
      return List.of();
    }

    // Use Editor + TargetElementUtil to find the declaration at the position
    // This is the key fix: file.findElementAt(offset) returns a reference/leaf element,
    // but we need the DECLARATION element to find all usages
    int offset = MiscUtil.positionToOffset(doc, pos);
    LOG.warn("FindUsagesCommand.execute: offset=" + offset + ", pos line=" + pos.getLine() + ", char=" + pos.getCharacter());
    var disposable = Disposer.newDisposable();
    try {
      return EditorUtil.computeWithEditor(disposable, file, pos, editor -> {
        LOG.warn("FindUsagesCommand.execute: editor created, caret offset=" + editor.getCaretModel().getOffset());

        int caretOffset = editor.getCaretModel().getOffset();

        // Use TargetElementUtil.findTargetElement with just editor and flags (uses caret position)
        // This finds the DECLARATION at the caret position, not the reference
        var target = TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().getAllAccepted());
        LOG.warn("FindUsagesCommand.execute: findTargetElement=" + target + ", class=" + (target != null ? target.getClass().getSimpleName() : "null"));

        if (target == null) {
          // Fallback: try walking up PSI tree from element at caret position
          var element = file.findElementAt(caretOffset);
          LOG.warn("FindUsagesCommand.execute: element at caret=" + element + ", class=" + (element != null ? element.getClass().getSimpleName() : "null"));

          if (element != null) {
            PsiElement current = element;
            for (int i = 0; i < 15 && current != null; i++) {
              var className = current.getClass().getSimpleName();
              LOG.warn("FindUsagesCommand.execute: walking up: " + className);
              if (className.contains("Class") || className.contains("Method") || className.contains("Field") ||
                  className.contains("Variable") || className.contains("Declaration") || className.contains("Parameter")) {
                target = current;
                LOG.warn("FindUsagesCommand.execute: Found target at " + className);
                break;
              }
              current = current.getParent();
            }
          }
          LOG.warn("FindUsagesCommand.execute: PSI walk target=" + target + ", class=" + (target != null ? target.getClass().getSimpleName() : "null"));
        }

        if (target == null) {
          return List.<Location>of();
        }

        LOG.warn("FindUsagesCommand.execute: target class=" + target.getClass().getSimpleName() + ", text=" + target.getText());

        var refs = ReferencesSearch.search(target).findAll();
        LOG.warn("FindUsagesCommand.execute: ReferencesSearch found " + refs.size() + " references");

        if (!refs.isEmpty()) {
          var locations = refs.stream()
              .map(PsiReference::getElement)
              .map(MiscUtil::psiElementToLocation)
              .filter(Objects::nonNull)
              .distinct()
              .collect(Collectors.toList());
          LOG.warn("FindUsagesCommand.execute: converted to " + locations.size() + " locations");
          return locations;
        }

        // Fallback to FindUsagesManager
        var results = findUsagesViaManager(project, target, file, ctx.getCancelToken());
        LOG.warn("FindUsagesCommand.execute: findUsagesViaManager found " + results.size() + " usages");

        return results;
      });
    } finally {
      Disposer.dispose(disposable);
    }
  }

  private static @NotNull List<@NotNull Location> findUsagesViaManager(@NotNull Project project,
                                                               @NotNull PsiElement target,
                                                               @NotNull PsiFile file,
                                                               @Nullable CancelChecker cancelToken) {
    var manager = ((FindManagerImpl) FindManager.getInstance(project)).getFindUsagesManager();
    var handler = manager.getFindUsagesHandler(target, FindUsagesHandlerFactory.OperationMode.USAGES_WITH_DEFAULT_OPTIONS);
    if (handler != null) {
      // Use LocalSearchScope like DocumentHighlightCommand does
      var searchScope = new LocalSearchScope(file);
      var refs = handler.findReferencesToHighlight(target, searchScope);
      LOG.warn("FindUsagesCommand.findUsagesViaManager: handler found " + refs.size() + " references via findReferencesToHighlight");
      if (!refs.isEmpty()) {
        return refs.stream()
            .filter(Objects::nonNull)
            .map(PsiReference::getElement)
            .map(MiscUtil::psiElementToLocation)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
      }
    }

    // Fallback to ReferencesSearch with LocalSearchScope
    var searchScope = new LocalSearchScope(file);
    var allRefs = ReferencesSearch.search(target, searchScope, false).findAll();
    LOG.warn("FindUsagesCommand.findUsagesViaManager: ReferencesSearch found " + allRefs.size() + " references");
    return allRefs.stream()
        .map(PsiReference::getElement)
        .map(MiscUtil::psiElementToLocation)
        .filter(Objects::nonNull)
        .distinct()
        .collect(Collectors.toList());
  }

  // Took this function from com.intellij.find.findUsages.FindUsagesManager.
  // Reference solution (Ruin0x11/intellij-lsp-server) used outdated constructor of FindUsagesManager.
  // Now this constructor is not exists.
  @NotNull
  private static UsageSearcher createUsageSearcher(PsiElement @NotNull [] primaryElements,
                                                   PsiElement @NotNull [] secondaryElements,
                                                   @NotNull FindUsagesHandlerBase handler,
                                                   @NotNull FindUsagesOptions options,
                                                   @NotNull Project project) throws PsiInvalidElementAccessException {
    FindUsagesOptions optionsClone = options.clone();
    return processor -> {
      Processor<UsageInfo> usageInfoProcessor = new CommonProcessors.UniqueProcessor<>(usageInfo -> {
        Usage usage = usageInfo != null ? UsageInfoToUsageConverter.convert(primaryElements, usageInfo) : null;
        return processor.process(usage);
      });
      PsiElement[] elements = ArrayUtil.mergeArrays(primaryElements, secondaryElements, PsiElement.ARRAY_FACTORY);

      optionsClone.fastTrack = new SearchRequestCollector(new SearchSession(elements));
      if (optionsClone.searchScope instanceof GlobalSearchScope) {
        // we will search in project scope always but warn if some usage is out of scope
        optionsClone.searchScope = optionsClone.searchScope.union(GlobalSearchScope.projectScope(project));
      }
      try {
        for (PsiElement element : elements) {
          if (!handler.processElementUsages(element, usageInfoProcessor, optionsClone)) {
            return;
          }

          for (CustomUsageSearcher searcher : CustomUsageSearcher.EP_NAME.getExtensionList()) {
            try {
              searcher.processElementUsages(element, processor, optionsClone);
            } catch (ProcessCanceledException e) {
              throw e;
            } catch (Exception e) {
              LOG.error(e);
            }
          }
        }

        PsiSearchHelper.getInstance(project).processRequests(optionsClone.fastTrack, ref -> {
          UsageInfo info = ref.getElement().isValid() ? new UsageInfo(ref) : null;
          return usageInfoProcessor.process(info);
        });
      } finally {
        optionsClone.fastTrack = null;
      }
    };
  }
}