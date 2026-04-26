package tf.locals.idealsp.server.lsp;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import tf.locals.idealsp.server.TestUtil;
import tf.locals.idealsp.server.generator.IdeaOffsetPositionConverter;
import tf.locals.idealsp.server.references.generators.TypeDefinitionTestGenerator;

import java.util.HashSet;

public class GotoTypeDefinitionTest extends LspServerTestWithEngineBase {
  @Override
  protected @NotNull String getTestDataRelativePath() {
    return "references/java/project-type-definition-integration";
  }

  @Test
  public void typeDefinition() {
    final var generator = new TypeDefinitionTestGenerator(getEngine(), new IdeaOffsetPositionConverter(server().getProject()));
    final var typeDefinitionTests = generator.generateTests();
    for (final var test : typeDefinitionTests) {
      final var params = test.params();
      final var answer = test.expected();

      final var future = server().getTextDocumentService().typeDefinition(params);
      final var actual = TestUtil.getNonBlockingEdt(future, 50000);

      assertEquals(answer, new HashSet<>(actual.getRight()));
    }
  }
}
