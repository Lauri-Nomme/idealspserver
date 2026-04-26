package tf.locals.idealsp.server.references;

import org.eclipse.lsp4j.ReferenceParams;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.TestUtil;
import tf.locals.idealsp.server.engine.TestEngine;
import tf.locals.idealsp.server.generator.IdeaOffsetPositionConverter;
import tf.locals.idealsp.server.references.generators.FindUsagesTestGenerator;

import java.util.HashSet;

@RunWith(JUnit4.class)
public class FindUsagesCommandTest extends ReferencesCommandTestBase<FindUsagesTestGenerator> {
  @Test
  public void testFindUsagesJava() {
    checkReferencesByDirectory("java/project-find-usages");
  }

/*
  @Test
  public void testFindUsagesPython() {
    checkReferencesByDirectory("python/project-find-usages");
  }
*/

  @Override
  protected @NotNull FindUsagesTestGenerator getGenerator(@NotNull TestEngine engine) {
    return new FindUsagesTestGenerator(engine, new IdeaOffsetPositionConverter(getProject()));
  }

  @Override
  protected @Nullable Object getActuals(@NotNull Object params) {
    final ReferenceParams refParams = (ReferenceParams) params;
    final var path = LspPath.fromLspUri(refParams.getTextDocument().getUri());
    final var future = new FindUsagesCommand(refParams.getPosition()).runAsync(getProject(), path);
    return new HashSet<>(TestUtil.getNonBlockingEdt(future, 50000));
  }
}
