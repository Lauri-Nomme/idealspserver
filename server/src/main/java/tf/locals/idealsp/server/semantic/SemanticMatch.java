package tf.locals.idealsp.server.semantic;

import org.eclipse.lsp4j.Position;

public class SemanticMatch {
  private String uri;
  private Position start;
  private Position end;
  private String matchedText;

  public SemanticMatch() {}

  public SemanticMatch(String uri, Position start, Position end, String matchedText) {
    this.uri = uri;
    this.start = start;
    this.end = end;
    this.matchedText = matchedText;
  }

  public String getUri() { return uri; }
  public void setUri(String uri) { this.uri = uri; }

  public Position getStart() { return start; }
  public void setStart(Position start) { this.start = start; }

  public Position getEnd() { return end; }
  public void setEnd(Position end) { this.end = end; }

  public String getMatchedText() { return matchedText; }
  public void setMatchedText(String matchedText) { this.matchedText = matchedText; }
}
