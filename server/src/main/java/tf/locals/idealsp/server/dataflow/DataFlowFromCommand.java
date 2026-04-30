package tf.locals.idealsp.server.dataflow;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.slicer.LanguageSlicing;
import com.intellij.slicer.SliceAnalysisParams;
import com.intellij.slicer.SliceUsage;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.commands.ExecutorContext;
import tf.locals.idealsp.server.commands.LspCommand;
import tf.locals.idealsp.server.util.EditorUtil;
import tf.locals.idealsp.server.util.MiscUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class DataFlowFromCommand extends LspCommand<List<DataFlowLocation>> {
    private static final Logger LOG = Logger.getInstance(DataFlowFromCommand.class);

    private final @NotNull Position pos;

    public DataFlowFromCommand(@NotNull Position pos) {
        this.pos = pos;
    }

    @Override
    protected @NotNull Supplier<@NotNull String> getMessageSupplier() {
        return () -> "Data Flow From call";
    }

    @Override
    protected boolean isCancellable() {
        return true;
    }

    @Override
    protected List<DataFlowLocation> execute(@NotNull ExecutorContext ctx) {
        List<DataFlowLocation> result = new ArrayList<>();

        var disposable = Disposer.newDisposable();
        try {
            EditorUtil.withEditor(disposable, ctx.getPsiFile(), pos, editor -> {
                int offset = MiscUtil.positionToOffset(MiscUtil.getDocument(ctx.getPsiFile()), pos);
                PsiElement element = ctx.getPsiFile().findElementAt(offset);

                if (element == null) {
                    LOG.warn("No element found at offset " + offset);
                    return;
                }

                SliceAnalysisParams params = new SliceAnalysisParams();
                params.dataFlowToThis = false;
                params.scope = new com.intellij.analysis.AnalysisScope(ctx.getProject());

                SliceUsage rootUsage = LanguageSlicing.getProvider(element).createRootUsage(element, params);

                List<SliceUsage> allUsages = new ArrayList<>();
                ProgressManager.getInstance().runProcess(
                    () -> rootUsage.processChildren(usage -> {
                        allUsages.add(usage);
                        return true;
                    }),
                    new ProgressIndicatorBase()
                );

                for (SliceUsage usage : allUsages) {
                    PsiElement sliceElement = usage.getElement();
                    if (sliceElement != null) {
                        addDataFlowLocation(sliceElement, result, ctx.getPsiFile());
                    }
                }
                
                if (result.isEmpty()) {
                    addDataFlowLocation(element, result, ctx.getPsiFile());
                }
            });
        } catch (Exception e) {
            LOG.error("Data flow analysis failed", e);
        } finally {
            Disposer.dispose(disposable);
        }

        return result;
    }

    private void addDataFlowLocation(@NotNull PsiElement element, @NotNull List<DataFlowLocation> result, @NotNull PsiFile file) {
        DataFlowLocation location = new DataFlowLocation();
        
        Range range = MiscUtil.getPsiElementRange(MiscUtil.getDocument(file), element);
        location.setRange(List.of(range));
        
        Location loc = new Location();
        if (file.getVirtualFile() != null) {
            loc.setUri(LspPath.fromVirtualFile(file.getVirtualFile()).toLspUri());
            loc.setRange(range);
            location.setLocation(loc);
        }
        
        result.add(location);
    }
}