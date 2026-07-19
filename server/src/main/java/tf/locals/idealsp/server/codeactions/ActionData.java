package tf.locals.idealsp.server.codeactions;

import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

final class ActionData {
  private String uri;
  private Range range;
  private String methodName;

  ActionData(@NotNull String uri, @NotNull Range range) {
    this.uri = uri;
    this.range = range;
  }

  ActionData(@NotNull String uri, @NotNull Range range, String methodName) {
    this.uri = uri;
    this.range = range;
    this.methodName = methodName;
  }

  public String getUri() {
    return uri;
  }

  @SuppressWarnings("unused") // used via reflection
  public void setUri(@NotNull String uri) {
    this.uri = uri;
  }

  public Range getRange() {
    return range;
  }

  public void setRange(@NotNull Range range) {
    this.range = range;
  }

  public String getMethodName() {
    return methodName;
  }

  public void setMethodName(String methodName) {
    this.methodName = methodName;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || obj.getClass() != this.getClass()) return false;
    var that = (ActionData) obj;
    return Objects.equals(this.uri, that.uri) &&
        Objects.equals(this.range, that.range) &&
        Objects.equals(this.methodName, that.methodName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uri, range, methodName);
  }

  @Override
  public String toString() {
    return "ActionData[" +
        "uri=" + uri + ", " +
        "range=" + range +
        (methodName != null ? ", methodName=" + methodName : "") + ']';
  }
}
