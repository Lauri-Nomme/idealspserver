package tf.locals.idealsp.server.lsp;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import tf.locals.idealsp.server.TestUtil;
import tf.locals.idealsp.server.generator.IdeaOffsetPositionConverter;
import tf.locals.idealsp.server.references.generators.DefinitionTestGenerator;

import java.util.HashSet;

public class GotoDefinitionTest extends LspServerTestWithEngineBase {


  @Override
  @NotNull
  protected String getTestDataRelativePath() {
    return "references/java/project-definition-integration";
  }

  @Test
  public void definition() {
    final var generator = new DefinitionTestGenerator(getEngine(), new IdeaOffsetPositionConverter(server().getProject()));
    final var definitionTests = generator.generateTests();
    for (final var test : definitionTests) {
      final var params = test.params();
      final var answer = test.expected();

      final var future = server().getTextDocumentService().definition(params);
      final var actual = TestUtil.getNonBlockingEdt(future, 50000);
      assertNotNull(actual);
      assertEquals(answer, new HashSet<>(actual.getRight()));
    }
  }

}
