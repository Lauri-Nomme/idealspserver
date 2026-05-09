package tf.locals.idealsp.server.symbol;

import com.intellij.ide.structureView.*;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.util.LspProgressIndicator;
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.ArrayList;
import java.util.List;

import static com.intellij.ide.actions.ViewStructureAction.createStructureViewModel;

@Service(Service.Level.PROJECT)
final public class DocumentSymbolService {
  @NotNull
  private final Project project;
  private static final Logger LOG = Logger.getInstance(DocumentSymbolService.class);

  public DocumentSymbolService(@NotNull Project project) {
    this.project = project;
  }

  @SuppressWarnings("deprecation")
  public @NotNull List<Either<SymbolInformation, DocumentSymbol>> computeDocumentSymbols(
      @NotNull LspPath path,
      @NotNull CancelChecker cancelChecker) {
    LOG.info("document symbol start");
    final var psiFile = MiscUtil.resolvePsiFile(project, path);
    if (psiFile == null) {
      return List.of();
    }
    var disposable = Disposer.newDisposable();
    try {
      return ProgressManager.getInstance().runProcess(() -> {

        StructureViewTreeElement root = getViewTreeElement(psiFile, disposable);
        if (root == null) {
          return List.of();
        }
        Document document = ReadAction.compute(() -> MiscUtil.getDocument(psiFile));
        assert document != null;

        var rootSymbol = processTree(root, psiFile, document);
        if (rootSymbol == null) {
          return List.of();
        }
        rootSymbol.setKind(SymbolKind.File);
        return List.of(Either.forRight(rootSymbol));
      }, new LspProgressIndicator(cancelChecker));
    } finally {
      WriteCommandAction.runWriteCommandAction(project, null, null,
          () -> Disposer.dispose(disposable), psiFile);
    }
  }

  @Nullable
  private StructureViewTreeElement getViewTreeElement(@NotNull PsiFile psiFile,
                                                      @NotNull Disposable parentDisposable) {

    // Use invokeAndWait to avoid write lock issue in IntelliJ 2026.1 tests
    FileEditor[] fileEditorHolder = new FileEditor[1];
    ApplicationManager.getApplication().invokeAndWait(() -> {
      fileEditorHolder[0] = TextEditorProvider.getInstance().createEditor(project, psiFile.getVirtualFile());
    });
    FileEditor fileEditor = fileEditorHolder[0];
    if (fileEditor == null) {
      LOG.warn("Cannot create editor for file: " + psiFile.getVirtualFile().getPath());
      return null;
    }
    Disposer.register(parentDisposable, fileEditor);
    StructureViewBuilder builder = ReadAction.compute(fileEditor::getStructureViewBuilder);
    if (builder == null) {
      return null;
    }
    StructureViewModel treeModel;
    if (builder instanceof TreeBasedStructureViewBuilder) {
      treeModel = ((TreeBasedStructureViewBuilder) builder).createStructureViewModel(EditorUtil.getEditorEx(fileEditor));
    } else {
      StructureView structureView = builder.createStructureView(fileEditor, project);
      treeModel = createStructureViewModel(project, fileEditor, structureView);
    }
    return treeModel.getRoot();
  }

  @Nullable
  private DocumentSymbol processTree(@NotNull TreeElement root,
                                     @NotNull PsiFile psiFile,
                                     @NotNull Document document) {

    DocumentSymbol documentSymbol = ReadAction.compute(() -> {
      var curSymbol = new DocumentSymbol();
      if (root instanceof StructureViewTreeElement viewElement) {
        curSymbol.setName(viewElement.getPresentation().getPresentableText());

        var value = viewElement.getValue();

        // Get kind and detail from PSI element directly
        if (value instanceof PsiMethod method) {
          curSymbol.setKind(SymbolKind.Method);
          setMethodDetail(curSymbol, method);
          if (method.getContainingFile() != psiFile) return null;
          var ideaRange = method.getTextRange();
          curSymbol.setRange(new Range(
              MiscUtil.offsetToPosition(document, ideaRange.getStartOffset()),
              MiscUtil.offsetToPosition(document, ideaRange.getEndOffset())));
          var selectionRange = new TextRange(method.getTextOffset(), method.getTextOffset());
          curSymbol.setSelectionRange(new Range(
              MiscUtil.offsetToPosition(document, selectionRange.getStartOffset()),
              MiscUtil.offsetToPosition(document, selectionRange.getEndOffset())));
        } else if (value instanceof PsiField field) {
          curSymbol.setKind(SymbolKind.Field);
          setFieldDetail(curSymbol, field);
          if (field.getContainingFile() != psiFile) return null;
          var ideaRange = field.getTextRange();
          curSymbol.setRange(new Range(
              MiscUtil.offsetToPosition(document, ideaRange.getStartOffset()),
              MiscUtil.offsetToPosition(document, ideaRange.getEndOffset())));
          var selectionRange = new TextRange(field.getTextOffset(), field.getTextOffset());
          curSymbol.setSelectionRange(new Range(
              MiscUtil.offsetToPosition(document, selectionRange.getStartOffset()),
              MiscUtil.offsetToPosition(document, selectionRange.getEndOffset())));
        } else if (value instanceof PsiClass psiClass) {
          curSymbol.setKind(SymbolKind.Class);
          setClassDetail(curSymbol, psiClass);
          if (psiClass.getContainingFile() != psiFile) return null;
          var ideaRange = psiClass.getTextRange();
          curSymbol.setRange(new Range(
              MiscUtil.offsetToPosition(document, ideaRange.getStartOffset()),
              MiscUtil.offsetToPosition(document, ideaRange.getEndOffset())));
          var selectionRange = new TextRange(psiClass.getTextOffset(), psiClass.getTextOffset());
          curSymbol.setSelectionRange(new Range(
              MiscUtil.offsetToPosition(document, selectionRange.getStartOffset()),
              MiscUtil.offsetToPosition(document, selectionRange.getEndOffset())));
        } else {
          curSymbol.setKind(SymbolKind.Object);
        }
      }
      return curSymbol;
    });
    if (documentSymbol == null) {
      return null; // if refers to another file
    }
    var children = new ArrayList<DocumentSymbol>();
    for (TreeElement child : ReadAction.compute(root::getChildren)) {
      var childSymbol = processTree(child, psiFile, document);
      if (childSymbol != null) { // if not refers to another file
        children.add(childSymbol);
      }
    }
    documentSymbol.setChildren(children);
    return documentSymbol;
  }

  private void setMethodDetail(@NotNull DocumentSymbol symbol, @NotNull PsiMethod method) {
    StringBuilder detail = new StringBuilder();
    if (method.hasModifierProperty(PsiModifier.PUBLIC)) {
      detail.append("public");
    } else if (method.hasModifierProperty(PsiModifier.PROTECTED)) {
      detail.append("protected");
    } else if (method.hasModifierProperty(PsiModifier.PRIVATE)) {
      detail.append("private");
    } else {
      detail.append("package");
    }
    detail.append(" ");
    detail.append(method.getName());
    detail.append("(");
    var params = method.getParameterList();
    var paramTypes = params.getParameters();
    for (int i = 0; i < paramTypes.length; i++) {
      if (i > 0) detail.append(", ");
      detail.append(paramTypes[i].getType().getPresentableText());
    }
    detail.append("): ");
    detail.append(method.getReturnType() != null ? method.getReturnType().getPresentableText() : "void");
    symbol.setDetail(detail.toString());
  }

  private void setFieldDetail(@NotNull DocumentSymbol symbol, @NotNull PsiField field) {
    StringBuilder detail = new StringBuilder();
    if (field.hasModifierProperty(PsiModifier.PUBLIC)) {
      detail.append("public");
    } else if (field.hasModifierProperty(PsiModifier.PROTECTED)) {
      detail.append("protected");
    } else if (field.hasModifierProperty(PsiModifier.PRIVATE)) {
      detail.append("private");
    } else {
      detail.append("package");
    }
    detail.append(" ");
    detail.append(field.getType().getPresentableText());
    symbol.setDetail(detail.toString());
  }

  private void setClassDetail(@NotNull DocumentSymbol symbol, @NotNull PsiClass psiClass) {
    if (psiClass.hasModifierProperty(PsiModifier.PUBLIC)) {
      symbol.setDetail("public");
    } else if (psiClass.hasModifierProperty(PsiModifier.PROTECTED)) {
      symbol.setDetail("protected");
    } else if (psiClass.hasModifierProperty(PsiModifier.PRIVATE)) {
      symbol.setDetail("private");
    }
  }

}
