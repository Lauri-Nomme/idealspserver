package tf.locals.idealsp.server.references;

import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.LocationLink;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.TestUtil;
import tf.locals.idealsp.server.engine.TestEngine;
import tf.locals.idealsp.server.generator.IdeaOffsetPositionConverter;
import tf.locals.idealsp.server.references.generators.DefinitionTestGenerator;

import java.util.HashSet;
import java.util.Set;


@RunWith(JUnit4.class)
public class DefinitionCommandTest extends ReferencesCommandTestBase<DefinitionTestGenerator> {
  @Test
  public void definitionJavaTest() {
    checkReferencesByDirectory("java/project-definition");
  }

/*
  @Test
  public void definitionPythonTest() {
    checkReferencesByDirectory("python/project-definition");
  }
*/


  @Override
  protected @NotNull DefinitionTestGenerator getGenerator(@NotNull TestEngine engine) {
    return new DefinitionTestGenerator(engine, new IdeaOffsetPositionConverter(getProject()));
  }

  @Override
  @Nullable
  protected Set<? extends LocationLink> getActuals(@NotNull Object params) {
    final DefinitionParams defParams = (DefinitionParams) params;
    final var path = LspPath.fromLspUri(defParams.getTextDocument().getUri());
    final var future = new FindDefinitionCommand(defParams.getPosition()).runAsync(getProject(), path);
    var actual = TestUtil.getNonBlockingEdt(future, 50000);
    if (actual == null) {
      return null;
    }
    return new HashSet<>(actual.getRight());
  }
}
