package org.rri.ideals.server.references;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.find.FindManager;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.find.impl.FindManagerImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;
import org.rri.ideals.server.ManagedDocuments;
import org.rri.ideals.server.commands.ExecutorContext;
import org.rri.ideals.server.commands.LspCommand;
import org.rri.ideals.server.util.EditorUtil;
import org.rri.ideals.server.util.MiscUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
      return List.of();
    }

    var project = ctx.getProject();
    if (project == null) {
      return List.of();
    }

    var disposable = Disposer.newDisposable();
    try {
      return EditorUtil.computeWithEditor(disposable, file, pos, editor -> {
        // Find the declaration element at cursor position
        var target = TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().getAllAccepted());

        if (target == null) {
          // Fallback: walk up PSI tree to find a named declaration
          var element = file.findElementAt(editor.getCaretModel().getOffset());
          if (element != null) {
            PsiElement current = element;
            for (int i = 0; i < 15 && current != null; i++) {
              var className = current.getClass().getSimpleName();
              if (className.contains("Class") || className.contains("Method") || className.contains("Field") ||
                  className.contains("Variable") || className.contains("Declaration") || className.contains("Parameter")) {
                target = current;
                break;
              }
              current = current.getParent();
            }
          }
        }

        if (target == null) {
          return List.<Location>of();
        }

        var manager = ((FindManagerImpl) FindManager.getInstance(project)).getFindUsagesManager();
        var handler = manager.getFindUsagesHandler(target, FindUsagesHandlerFactory.OperationMode.USAGES_WITH_DEFAULT_OPTIONS);

        // Try global scope first (fast, uses word index - works when project has content roots)
        var globalScope = GlobalSearchScope.projectScope(project);
        var globalRefs = new java.util.HashSet<PsiReference>();
        if (handler != null) {
          globalRefs.addAll(handler.findReferencesToHighlight(target, globalScope));
        }
        globalRefs.addAll(ReferencesSearch.search(target, globalScope, false).findAll());

        java.util.Set<PsiReference> allRefs;
        if (!globalRefs.isEmpty()) {
          // Global index works, use its results
          allRefs = globalRefs;
          LOG.info("FindUsagesCommand: using global scope, found " + globalRefs.size() + " references");
        } else {
          // Fallback: search across all managed (open) documents via LocalSearchScope.
          // GlobalSearchScope relies on IntelliJ's word index which requires configured
          // content roots. In LSP context, files may not be part of any module, so the
          // index is empty. LocalSearchScope searches PSI trees directly.
          allRefs = new java.util.HashSet<>();
          var managedScope = buildManagedFilesScope(project, file);
          if (handler != null) {
            allRefs.addAll(handler.findReferencesToHighlight(target, managedScope));
          }
          allRefs.addAll(ReferencesSearch.search(target, managedScope, false).findAll());
          LOG.info("FindUsagesCommand: global scope empty, used managed-files fallback, found " + allRefs.size() + " references");
        }

        LOG.info("FindUsagesCommand: found " + allRefs.size() + " references (global: " + globalRefs.size() + ")");

        return allRefs.stream()
            .filter(Objects::nonNull)
            .map(PsiReference::getElement)
            .map(MiscUtil::psiElementToLocation)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
      });
    } finally {
      Disposer.dispose(disposable);
    }
  }

  /**
   * Build a LocalSearchScope from all files managed by the LSP server (opened via didOpen).
   * This is necessary because GlobalSearchScope relies on IntelliJ's word index which
   * requires properly configured content roots. In the LSP context, files are opened
   * individually and may not be part of any module's content roots.
   */
  @NotNull
  private static LocalSearchScope buildManagedFilesScope(@NotNull Project project, @NotNull PsiFile currentFile) {
    var managedDocs = project.getService(ManagedDocuments.class);
    var psiFiles = new ArrayList<PsiFile>();
    psiFiles.add(currentFile);

    managedDocs.forEach(path -> {
      var psiFile = MiscUtil.resolvePsiFile(project, path);
      if (psiFile != null && psiFile != currentFile) {
        psiFiles.add(psiFile);
      }
    });

    LOG.info("FindUsagesCommand: searching across " + psiFiles.size() + " managed files");
    return new LocalSearchScope(psiFiles.toArray(PsiFile.EMPTY_ARRAY));
  }
}
