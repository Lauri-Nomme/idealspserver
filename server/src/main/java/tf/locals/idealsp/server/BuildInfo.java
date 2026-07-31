package tf.locals.idealsp.server;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Reads build metadata (version + git commit) embedded into the jar at build time. */
public final class BuildInfo {
  public static final String VERSION;
  public static final String COMMIT;

  static {
    var props = new Properties();
    try (InputStream in = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
      if (in != null) props.load(in);
    } catch (IOException e) {
      // keep defaults
    }
    VERSION = props.getProperty("version", "unknown");
    COMMIT = props.getProperty("git.commit", "unknown");
  }

  private BuildInfo() {}

  /** e.g. "1.0.12-g3f2a1c9" or "1.0-SNAPSHOT-gunknown". */
  @NotNull
  public static String fullVersion() {
    return VERSION + "-g" + COMMIT;
  }
}
