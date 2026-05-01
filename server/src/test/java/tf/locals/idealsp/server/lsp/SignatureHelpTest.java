package tf.locals.idealsp.server.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Tuple;
import org.junit.Test;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.TestUtil;
import tf.locals.idealsp.server.util.MiscUtil;

import java.nio.file.Files;
import java.util.List;

public class SignatureHelpTest extends LspServerTestBase {
  @Override
  protected String getProjectRelativePath() {
    return "signature-help";
  }

  @Test
  public void signatureHelp() {
    final var filePath = LspPath.fromLocalPath(getProjectPath().resolve("src/Test.java"));

    final var didOpenParams = MiscUtil.with(new DidOpenTextDocumentParams(),
        params -> params.setTextDocument(MiscUtil.with(new TextDocumentItem(), item -> {
          item.setUri(filePath.toLspUri());
          item.setText(MiscUtil.makeThrowsUnchecked(() -> Files.readString(filePath.toPath())));
          item.setVersion(1);
        })));

    server().getTextDocumentService().didOpen(didOpenParams);

    SignatureHelpParams params = new SignatureHelpParams();
    params.setTextDocument(new TextDocumentIdentifier(filePath.toLspUri()));
    params.setPosition(new Position(4, 8));
    var actual =
        TestUtil.getNonBlockingEdt(server().getTextDocumentService().signatureHelp(params), 30000);
    var expected = new SignatureHelp();
    var firstSignatureInformation = new SignatureInformation();
    firstSignatureInformation.setActiveParameter(0);
    firstSignatureInformation.setLabel("int x");

    var firstParameterInformation = new ParameterInformation();
    firstParameterInformation.setLabel(Tuple.two(0, 5));

    firstSignatureInformation.setParameters(List.of(firstParameterInformation));

    expected.setSignatures(List.of(firstSignatureInformation));
    assertEquals(expected, actual);
  }
}
