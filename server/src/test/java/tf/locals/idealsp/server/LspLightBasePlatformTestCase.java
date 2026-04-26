package tf.locals.idealsp.server;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

public abstract class LspLightBasePlatformTestCase extends LightJavaCodeInsightFixtureTestCase {
  @Override
  protected boolean isIconRequired() {
    return true;
  }
}
