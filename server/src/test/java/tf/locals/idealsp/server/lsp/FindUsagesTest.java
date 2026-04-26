package tf.locals.idealsp.server.lsp;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.junit.jupiter.api.Timeout;
import tf.locals.idealsp.server.TestUtil;
import tf.locals.idealsp.server.generator.IdeaOffsetPositionConverter;
import tf.locals.idealsp.server.references.generators.FindUsagesTestGenerator;

import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class FindUsagesTest extends LspServerTestWithEngineBase {
  @Override
  protected @NotNull String getTestDataRelativePath() {
    return "references/java/project-find-usages-integration";
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void findUsages() {
    final var generator = new FindUsagesTestGenerator(getEngine(), new IdeaOffsetPositionConverter(server().getProject()));
    final var definitionTests = generator.generateTests();
    for (final var test : definitionTests) {
      final var params = test.params();
      final var answer = test.expected();

      final var future = server().getTextDocumentService().references(params);
      final var actual = Optional.ofNullable(TestUtil.getNonBlockingEdt(future, 50000));

      assertTrue(actual.isPresent());
      assertEquals(answer, new HashSet<>(actual.get()));
    }
  }
}
