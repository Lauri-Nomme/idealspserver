package tf.locals.idealsp.server.diagnostics;

import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import tf.locals.idealsp.server.LspContext;
import tf.locals.idealsp.server.LspLightBasePlatformTestCase;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.mocks.MockLanguageClient;

public abstract class DiagnosticsTestBase extends LspLightBasePlatformTestCase {
  @Before
  public void setupContext() {
    LspContext.createContext(getProject(),
        new MockLanguageClient(),
        new ClientCapabilities()
    );
  }

  @NotNull
  protected MockLanguageClient getClient() {
    return (MockLanguageClient) LspContext.getContext(getProject()).getClient();
  }

  @NotNull
  protected PublishDiagnosticsParams runAndGetDiagnostics(@NotNull PsiFile file) {
    getClient().resetDiagnosticsResult();
    getProject().getService(DiagnosticsService.class).launchDiagnostics(LspPath.fromVirtualFile(file.getVirtualFile()));
    return getClient().waitAndGetDiagnosticsPublished();
  }
}
