package tf.locals.idealsp.server.typehierarchy;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.commands.ExecutorContext;
import tf.locals.idealsp.server.commands.LspCommand;
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class PrepareTypeHierarchyCommand extends LspCommand<List<TypeHierarchyItem>> {
  private static final Logger LOG = Logger.getInstance(PrepareTypeHierarchyCommand.class);

  private final @NotNull Position pos;

  public PrepareTypeHierarchyCommand(@NotNull Position pos) {
    this.pos = pos;
  }

  @Override
  protected @NotNull Supplier<@NotNull String> getMessageSupplier() {
    return () -> "PrepareTypeHierarchy call";
  }

  @Override
  protected boolean isCancellable() {
    return true;
  }

  @Override
  protected List<TypeHierarchyItem> execute(@NotNull ExecutorContext ctx) {
    List<TypeHierarchyItem> result = new ArrayList<>();
    int offset = MiscUtil.positionToOffset(MiscUtil.getDocument(ctx.getPsiFile()), pos);
    PsiElement element = ctx.getPsiFile().findElementAt(offset);
    if (element == null) return result;

    PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
    if (psiClass == null || psiClass instanceof PsiAnonymousClass) return result;

    TypeHierarchyItem item = convertToTypeHierarchyItem(psiClass, ctx.getPsiFile());
    if (item != null) result.add(item);

    return result;
  }

  @Nullable
  static TypeHierarchyItem convertToTypeHierarchyItem(@NotNull PsiClass psiClass, @NotNull PsiFile file) {
    if (file.getVirtualFile() == null) return null;

    String name = psiClass.getName();
    if (name == null) return null;

    SymbolKind kind;
    if (psiClass.isInterface()) {
      kind = SymbolKind.Interface;
    } else if (psiClass.isEnum()) {
      kind = SymbolKind.Enum;
    } else {
      kind = SymbolKind.Class;
    }

    String uri = LspPath.fromVirtualFile(file.getVirtualFile()).toLspUri();
    Range range = MiscUtil.getPsiElementRange(MiscUtil.getDocument(file), psiClass);
    if (range == null) return null;

    Range selectionRange;
    PsiElement nameIdentifier = psiClass.getNameIdentifier();
    if (nameIdentifier != null) {
      selectionRange = MiscUtil.getPsiElementRange(MiscUtil.getDocument(file), nameIdentifier);
    } else {
      selectionRange = range;
    }

    String detail = psiClass.getQualifiedName();

    TypeHierarchyItem item = new TypeHierarchyItem(name, kind, uri, range, selectionRange, detail);

    Map<String, Object> data = new HashMap<>();
    data.put("fileUrl", file.getVirtualFile().getUrl());
    data.put("className", name);
    if (detail != null) data.put("qualifiedName", detail);
    item.setData(data);

    return item;
  }

  @Nullable
  static PsiClass resolveClassFromItem(@NotNull Project project, @NotNull TypeHierarchyItem item) {
    Object data = item.getData();
    if (data == null) return null;

    String fileUrl = null;
    String className = null;

    if (data instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> dataMap = (Map<String, Object>) data;
      fileUrl = (String) dataMap.get("fileUrl");
      className = (String) dataMap.get("className");
    } else if (data instanceof JsonObject) {
      JsonObject json = (JsonObject) data;
      fileUrl = json.has("fileUrl") ? json.get("fileUrl").getAsString() : null;
      className = json.has("className") ? json.get("className").getAsString() : null;
    } else {
      return null;
    }

    if (fileUrl == null || className == null) return null;

    LspPath lspPath = LspPath.fromLspUri(fileUrl);
    if (lspPath == null) return null;
    var virtualFile = lspPath.findVirtualFile();
    if (virtualFile == null) return null;

    PsiFile psiFile = MiscUtil.resolvePsiFile(project, lspPath);
    if (!(psiFile instanceof PsiClassOwner)) return null;

    PsiClassOwner classOwner = (PsiClassOwner) psiFile;
    for (PsiClass cls : classOwner.getClasses()) {
      if (className.equals(cls.getName())) return cls;
    }
    return null;
  }
}
