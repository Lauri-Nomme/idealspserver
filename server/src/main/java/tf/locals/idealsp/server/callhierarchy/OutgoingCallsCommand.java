package tf.locals.idealsp.server.callhierarchy;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.*;

public class OutgoingCallsCommand {
    private static final Logger LOG = Logger.getInstance(OutgoingCallsCommand.class);

    private final @NotNull CallHierarchyItem item;

    public OutgoingCallsCommand(@NotNull CallHierarchyItem item) {
        this.item = item;
    }

    public List<CallHierarchyOutgoingCall> execute(@NotNull Project project) {
        List<CallHierarchyOutgoingCall> result = new ArrayList<>();

        LOG.warn("OutgoingCallsCommand.execute: item=" + item.getName() + " data=" + item.getData());

        try {
            PsiElement targetElement = IncomingCallsCommand.resolveElementFromItem(project, item);
            LOG.warn("Resolved element: " + targetElement);
            if (targetElement == null || !(targetElement instanceof PsiMethod)) {
                LOG.warn("Target is not a method, returning empty");
                return result;
            }

            PsiMethod targetMethod = (PsiMethod) targetElement;
            Map<PsiMethod, List<PsiElement>> calleesMap = new HashMap<>();

            targetMethod.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                    super.visitMethodCallExpression(expression);
                    PsiMethod calledMethod = resolveMethodCall(expression);
                    if (calledMethod != null) {
                        calleesMap.computeIfAbsent(calledMethod, k -> new ArrayList<>()).add(expression);
                    }
                }

                @Override
                public void visitNewExpression(@NotNull PsiNewExpression expression) {
                    super.visitNewExpression(expression);
                    PsiMethod constructor = expression.resolveConstructor();
                    if (constructor != null) {
                        calleesMap.computeIfAbsent(constructor, k -> new ArrayList<>()).add(expression);
                    }
                }
            });

            LOG.warn("Found " + calleesMap.size() + " callee methods");

            for (Map.Entry<PsiMethod, List<PsiElement>> entry : calleesMap.entrySet()) {
                PsiMethod callee = entry.getKey();
                List<PsiElement> callSites = entry.getValue();

                CallHierarchyItem calleeItem = PrepareCallHierarchyCommand.convertToCallHierarchyItem(
                        callee, callee.getContainingFile());
                if (calleeItem == null) continue;

                List<Range> fromRanges = new ArrayList<>();
                for (PsiElement callSite : callSites) {
                    Range range = MiscUtil.getPsiElementRange(MiscUtil.getDocument(callSite.getContainingFile()), callSite);
                    if (range != null) fromRanges.add(range);
                }

                CallHierarchyOutgoingCall outgoingCall = new CallHierarchyOutgoingCall();
                outgoingCall.setTo(calleeItem);
                outgoingCall.setFromRanges(fromRanges);
                result.add(outgoingCall);
            }

        } catch (Exception e) {
            LOG.error("Error in OutgoingCallsCommand", e);
        }

        LOG.warn("Returning " + result.size() + " outgoing calls");
        return result;
    }

    private static PsiMethod resolveMethodCall(@NotNull PsiMethodCallExpression expression) {
        PsiReference reference = expression.getMethodExpression().getReference();
        if (reference == null) return null;
        PsiElement resolved = reference.resolve();
        return resolved instanceof PsiMethod ? (PsiMethod) resolved : null;
    }
}