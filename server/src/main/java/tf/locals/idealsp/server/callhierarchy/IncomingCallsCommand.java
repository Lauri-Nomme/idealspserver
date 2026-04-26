package tf.locals.idealsp.server.callhierarchy;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.ManagedDocuments;
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.*;

public class IncomingCallsCommand {
    private static final Logger LOG = Logger.getInstance(IncomingCallsCommand.class);

    private final @NotNull CallHierarchyItem item;

    public IncomingCallsCommand(@NotNull CallHierarchyItem item) {
        this.item = item;
    }

    public List<CallHierarchyIncomingCall> execute(@NotNull Project project) {
        List<CallHierarchyIncomingCall> result = new ArrayList<>();

        LOG.warn("IncomingCallsCommand.execute: item=" + item.getName() + " data=" + item.getData());

        try {
            PsiElement targetElement = resolveElementFromItem(project, item);
            LOG.warn("Resolved element: " + targetElement);
            if (targetElement == null || !(targetElement instanceof PsiMethod)) {
                LOG.warn("Target is not a method, returning empty");
                return result;
            }

            PsiMethod targetMethod = (PsiMethod) targetElement;
            Map<PsiMethod, List<PsiElement>> callersMap = new HashMap<>();

            // Use global scope first (fast, uses word index)
            var globalScope = GlobalSearchScope.projectScope(project);
            var callerRefs = ReferencesSearch.search(targetMethod, globalScope, false).findAll();

            // Fallback: use everything scope if project scope empty
            if (callerRefs.isEmpty()) {
                LOG.warn("Project scope empty, trying everything scope");
                var everythingScope = GlobalSearchScope.everythingScope(project);
                callerRefs = ReferencesSearch.search(targetMethod, everythingScope, false).findAll();
                LOG.warn("Everything scope found " + callerRefs.size() + " references");
            }

            // Fallback: search across all managed (open) documents if no results
            if (callerRefs.isEmpty()) {
                LOG.warn("Global scope empty, trying managed-files fallback");
                var managedScope = buildManagedFilesScope(project, targetMethod.getContainingFile());
                callerRefs = ReferencesSearch.search(targetMethod, managedScope, false).findAll();
                LOG.warn("Managed scope found " + callerRefs.size() + " references");
            } else {
                LOG.warn("Found " + callerRefs.size() + " references via global scope");
            }

            for (PsiReference reference : callerRefs) {
                PsiElement referenceElement = reference.getElement();
                if (referenceElement == null) continue;

                PsiMethod callerMethod = findContainingMethod(referenceElement);
                if (callerMethod != null) {
                    callersMap.computeIfAbsent(callerMethod, k -> new ArrayList<>()).add(referenceElement);
                }
            }

            LOG.warn("Found " + callersMap.size() + " caller methods via ReferencesSearch");

            for (Map.Entry<PsiMethod, List<PsiElement>> entry : callersMap.entrySet()) {
                PsiMethod caller = entry.getKey();
                List<PsiElement> callSites = entry.getValue();

                CallHierarchyItem callerItem = PrepareCallHierarchyCommand.convertToCallHierarchyItem(
                        caller, caller.getContainingFile());
                if (callerItem == null) continue;

                List<Range> fromRanges = new ArrayList<>();
                for (PsiElement callSite : callSites) {
                    Range range = MiscUtil.getPsiElementRange(MiscUtil.getDocument(callSite.getContainingFile()), callSite);
                    if (range != null) fromRanges.add(range);
                }

                CallHierarchyIncomingCall incomingCall = new CallHierarchyIncomingCall();
                incomingCall.setFrom(callerItem);
                incomingCall.setFromRanges(fromRanges);
                result.add(incomingCall);
            }

        } catch (Exception e) {
            LOG.error("Error in IncomingCallsCommand", e);
        }

        LOG.warn("Returning " + result.size() + " incoming calls");
        return result;
    }

    static PsiElement resolveElementFromItem(@NotNull Project project, @NotNull CallHierarchyItem item) {
        Object data = item.getData();
        LOG.warn("resolveElementFromItem: data type=" + (data == null ? "null" : data.getClass()));

        String fileUrl = null;
        String elementName = null;
        Boolean isMethod = false;
        Integer paramCount = -1;

        if (data instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dataMap = (Map<String, Object>) data;
            fileUrl = (String) dataMap.get("fileUrl");
            elementName = (String) dataMap.get("elementName");
            isMethod = (Boolean) dataMap.getOrDefault("isMethod", false);
            paramCount = (Integer) dataMap.getOrDefault("paramCount", -1);
        } else if (data instanceof JsonObject) {
            JsonObject json = (JsonObject) data;
            fileUrl = json.has("fileUrl") ? json.get("fileUrl").getAsString() : null;
            elementName = json.has("elementName") ? json.get("elementName").getAsString() : null;
            isMethod = json.has("isMethod") && json.get("isMethod").getAsBoolean();
            paramCount = json.has("paramCount") ? json.get("paramCount").getAsInt() : -1;
            LOG.warn("Parsed from JsonObject: fileUrl=" + fileUrl + " elementName=" + elementName);
        } else {
            LOG.warn("Data is not a Map or JsonObject, returning null");
            return null;
        }

        LOG.warn("fileUrl=" + fileUrl + " elementName=" + elementName + " isMethod=" + isMethod + " paramCount=" + paramCount);

        if (fileUrl == null || elementName == null) {
            LOG.warn("fileUrl or elementName is null");
            return null;
        }

        LspPath lspPath = LspPath.fromLspUri(fileUrl);
        LOG.warn("lspPath=" + lspPath);
        if (lspPath == null) {
            LOG.warn("lspPath is null");
            return null;
        }
        VirtualFile virtualFile = lspPath.findVirtualFile();
        LOG.warn("virtualFile=" + virtualFile);
        if (virtualFile == null) {
            LOG.warn("virtualFile is null");
            return null;
        }

        PsiFile psiFile = MiscUtil.resolvePsiFile(project, lspPath);
        LOG.warn("psiFile=" + psiFile);
        if (psiFile == null) {
            LOG.warn("psiFile is null");
            return null;
        }

        if (isMethod && paramCount != null) {
            if (psiFile instanceof PsiJavaFile) {
                PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                for (PsiClass cls : javaFile.getClasses()) {
                    for (PsiMethod method : cls.findMethodsByName(elementName, false)) {
                        if (method.getParameterList().getParameters().length == paramCount) {
                            LOG.warn("Found method: " + method.getName());
                            return method;
                        }
                    }
                }
            }
        }

        LOG.warn("Method not found");
        return null;
    }

    private static PsiMethod findContainingMethod(@NotNull PsiElement element) {
        PsiElement current = element;
        while (current != null) {
            if (current instanceof PsiMethod) return (PsiMethod) current;
            current = current.getParent();
        }
        return null;
    }

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

        LOG.warn("IncomingCallsCommand: searching across " + psiFiles.size() + " managed files");
        return new LocalSearchScope(psiFiles.toArray(PsiFile.EMPTY_ARRAY));
    }
}