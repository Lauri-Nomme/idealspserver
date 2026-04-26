package org.rri.ideals.server.callhierarchy;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.jetbrains.annotations.NotNull;
import org.rri.ideals.server.LspPath;
import org.rri.ideals.server.commands.ExecutorContext;
import org.rri.ideals.server.commands.LspCommand;
import org.rri.ideals.server.util.EditorUtil;
import org.rri.ideals.server.util.MiscUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class PrepareCallHierarchyCommand extends LspCommand<List<CallHierarchyItem>> {
    private static final Logger LOG = Logger.getInstance(PrepareCallHierarchyCommand.class);

    private final @NotNull Position pos;

    public PrepareCallHierarchyCommand(@NotNull Position pos) {
        this.pos = pos;
    }

    @Override
    protected @NotNull Supplier<@NotNull String> getMessageSupplier() {
        return () -> "PrepareCallHierarchy call";
    }

    @Override
    protected boolean isCancellable() {
        return true;
    }

    @Override
    protected List<CallHierarchyItem> execute(@NotNull ExecutorContext ctx) {
        List<CallHierarchyItem> result = new ArrayList<>();

        var disposable = Disposer.newDisposable();
        try {
            EditorUtil.withEditor(disposable, ctx.getPsiFile(), pos, editor -> {
                int offset = MiscUtil.positionToOffset(MiscUtil.getDocument(ctx.getPsiFile()), pos);
                PsiElement element = findCallableElement(editor, offset, ctx.getPsiFile());

                if (element == null) {
                    return;
                }

                // If it's a method, also check for overriding methods to include in the hierarchy
                if (element instanceof PsiMethod) {
                    PsiMethod method = (PsiMethod) element;
                    // Add the primary method
                    CallHierarchyItem item = convertToCallHierarchyItem(method, ctx.getPsiFile());
                    if (item != null) {
                        result.add(item);
                    }

                    // Also add any overriding methods
                    OverridingMethodsSearch.search(method).forEach(overridingMethod -> {
                        CallHierarchyItem overridingItem = convertToCallHierarchyItem(overridingMethod, overridingMethod.getContainingFile());
                        if (overridingItem != null) {
                            result.add(overridingItem);
                        }
                        return true;
                    });
                } else {
                    CallHierarchyItem item = convertToCallHierarchyItem(element, ctx.getPsiFile());
                    if (item != null) {
                        result.add(item);
                    }
                }
            });
        } finally {
            Disposer.dispose(disposable);
        }

        return result;
    }

    static PsiElement findCallableElement(@NotNull Editor editor, int offset, @NotNull PsiFile file) {
        PsiElement elementAt = file.findElementAt(offset);
        if (elementAt == null) {
            return null;
        }

        // Walk up the PSI tree to find a callable element
        PsiElement current = elementAt;
        while (current != null) {
            if (current instanceof PsiMethod || current instanceof PsiLambdaExpression) {
                return current;
            }
            // If we hit a class/field, stop searching
            if (current instanceof PsiClass || current instanceof PsiField) {
                break;
            }
            current = current.getParent();
        }

        return null;
    }

    static CallHierarchyItem convertToCallHierarchyItem(@NotNull PsiElement element, @NotNull PsiFile file) {
        if (!(element instanceof PsiNamedElement)) {
            return null;
        }

        PsiNamedElement namedElement = (PsiNamedElement) element;
        CallHierarchyItem item = new CallHierarchyItem();

        // Name
        item.setName(namedElement.getName());

        // Kind
        if (element instanceof PsiMethod) {
            item.setKind(SymbolKind.Method);
        } else if (element instanceof PsiLambdaExpression) {
            item.setKind(SymbolKind.Function);
        } else {
            return null; // Not a callable
        }

        // URI
        if (file.getVirtualFile() == null) {
            return null;
        }
        item.setUri(LspPath.fromVirtualFile(file.getVirtualFile()).toLspUri());

        // Range and selection range
        Range range = MiscUtil.getPsiElementRange(MiscUtil.getDocument(file), element);
        item.setRange(range);

        // Selection range - typically the name identifier
        PsiElement nameIdentifier = null;
        if (element instanceof PsiMethod) {
            nameIdentifier = ((PsiMethod) element).getNameIdentifier();
        }
        if (nameIdentifier != null) {
            item.setSelectionRange(MiscUtil.getPsiElementRange(MiscUtil.getDocument(file), nameIdentifier));
        } else {
            item.setSelectionRange(range);
        }

        // Detail (containing class)
        PsiClass containingClass = null;
        if (element instanceof PsiMethod) {
            containingClass = ((PsiMethod) element).getContainingClass();
        }
        if (containingClass != null) {
            item.setDetail(containingClass.getQualifiedName());
        }

        // Data - store enough info to relocate the element later
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("fileUrl", file.getVirtualFile().getUrl());
        if (namedElement.getName() != null) {
            data.put("elementName", namedElement.getName());
        }
        if (element instanceof PsiMethod) {
            PsiMethod method = (PsiMethod) element;
            data.put("isMethod", true);
            // Store parameter count to help identify the method
            data.put("paramCount", method.getParameterList().getParameters().length);
        }
        item.setData(data);

        return item;
    }
}
