package tf.locals.idealsp.server.refactoring;

import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class RefactorParams {
  private @NotNull String uri;
  private @NotNull String type;
  private @NotNull Position position;
  private @Nullable String name;
  private @Nullable String targetPackageUri;

  public RefactorParams() {}

  public RefactorParams(@NotNull String uri, @NotNull String type, @NotNull Position position, @Nullable String name) {
    this.uri = uri;
    this.type = type;
    this.position = position;
    this.name = name;
  }

  public @NotNull String getUri() { return uri; }
  public void setUri(@NotNull String uri) { this.uri = uri; }
  public @NotNull String getType() { return type; }
  public void setType(@NotNull String type) { this.type = type; }
  public @NotNull Position getPosition() { return position; }
  public void setPosition(@NotNull Position position) { this.position = position; }
  public @Nullable String getName() { return name; }
  public void setName(@Nullable String name) { this.name = name; }
  public @Nullable String getTargetPackageUri() { return targetPackageUri; }
  public void setTargetPackageUri(@Nullable String targetPackageUri) { this.targetPackageUri = targetPackageUri; }
}
