package tf.locals.idealsp.server.semantic;

import com.intellij.openapi.application.ApplicationManager;
import org.eclipse.lsp4j.Position;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import tf.locals.idealsp.server.LspLightBasePlatformTestCase;
import tf.locals.idealsp.server.LspPath;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(JUnit4.class)
public class SemanticSearchFeatureTest extends LspLightBasePlatformTestCase {
  private LspPath testFilePath;

  @Override
  protected String getTestDataPath() {
    return Paths.get("test-data/semantic").toAbsolutePath().toString();
  }

  @Before
  public void copyFileToProject() {
    var copied = myFixture.copyDirectoryToProject("", "");
    var virtualFile = copied.findChild("SemanticSearchTest.java");
    assertNotNull("Test data file not found", virtualFile);
    testFilePath = LspPath.fromVirtualFile(virtualFile);
  }

  @Test
  public void testSearchFieldDeclarations() {
    var results = runSearch("$Type$ $FieldName$;", null);
    assertNotNull("search returned null", results);
    assertTrue("expected at least 1 field match", results.size() >= 1);

    var text = results.stream().map(SemanticMatch::getMatchedText).toList();
    assertTrue("should find 'name' field", text.stream().anyMatch(t -> t.contains("name")));
    assertTrue("should find 'count' field", text.stream().anyMatch(t -> t.contains("count")));
  }

  @Test
  public void testSearchMethodDeclarations() {
    var results = runSearch("$ReturnType$ $MethodName$();", null);
    assertNotNull("search returned null", results);
    assertTrue("expected at least 1 no-arg method match", results.size() >= 1);

    var matchedText = results.stream().map(SemanticMatch::getMatchedText).toList();
    assertTrue("should find getName()", matchedText.stream().anyMatch(t -> t.contains("getName")));
    assertTrue("should find getCount()", matchedText.stream().anyMatch(t -> t.contains("getCount")));
  }

  @Test
  public void testSearchMethodDeclarationsWithParams() {
    var results = runSearch("$ReturnType$ $MethodName$($ParameterType$ $Parameter$);", null);
    assertNotNull("search returned null", results);
    assertTrue("expected at least 1 method with params", results.size() >= 1);

    var matchedText = results.stream().map(SemanticMatch::getMatchedText).toList();
    assertTrue("should find setName(String)", matchedText.stream().anyMatch(t -> t.contains("setName")));
    assertTrue("should find process(List)", matchedText.stream().anyMatch(t -> t.contains("process")));
  }

  @Test
  public void testSearchWithTypeConstraint() {
    var constraints = Map.of("$Type$", Map.of("regex", "String"));
    var results = runSearch("$Type$ $FieldName$;", constraints);
    assertNotNull("search returned null", results);
    assertTrue("expected at least 1 String field match", results.size() >= 1);

    var matchedText = results.stream().map(SemanticMatch::getMatchedText).toList();
    assertTrue("should find 'name' field of type String",
        matchedText.stream().anyMatch(t -> t.contains("String") && t.contains("name")));
  }

  @Test
  public void testSearchWithRegexConstraint() {
    var constraints = Map.of("$MethodName$", Map.of("regex", "get.*"));
    var results = runSearch("$ReturnType$ $MethodName$();", constraints);
    assertNotNull("search returned null", results);
    assertTrue("expected at least 1 getter match", results.size() >= 1);

    var matchedText = results.stream().map(SemanticMatch::getMatchedText).toList();
    assertTrue("should find getName()", matchedText.stream().anyMatch(t -> t.contains("getName")));
    assertTrue("should find getCount()", matchedText.stream().anyMatch(t -> t.contains("getCount")));
  }

  @Test
  public void testSearchWithMinCountConstraint() {
    var constraints = Map.of("$Parameter$", Map.of("minCount", "1"));
    var results = runSearch("$ReturnType$ $MethodName$($ParameterType$ $Parameter$);", constraints);
    assertNotNull("search returned null", results);
    assertTrue("expected at least 1 method with parameters", results.size() >= 1);

    var matchedText = results.stream().map(SemanticMatch::getMatchedText).toList();
    assertTrue("should find setName(String)", matchedText.stream().anyMatch(t -> t.contains("setName")));
    assertTrue("should find process(List)", matchedText.stream().anyMatch(t -> t.contains("process")));
  }

  @Test
  public void testSearchWithNotRegexConstraint() {
    var constraints = Map.of("$MethodName$", Map.of("notRegex", "get.*"));
    var results = runSearch("$ReturnType$ $MethodName$($ParameterType$ $Parameter$);", constraints);
    assertNotNull("search returned null", results);
    assertTrue("expected at least 1 non-getter method match", results.size() >= 1);

    var matchedText = results.stream().map(SemanticMatch::getMatchedText).toList();
    assertTrue("should find setName()", matchedText.stream().anyMatch(t -> t.contains("setName")));
    assertTrue("should find process()", matchedText.stream().anyMatch(t -> t.contains("process")));
  }

  @Test
  public void testSearchPositionsAreCorrect() {
    var results = runSearch("$Type$ $FieldName$;", null);
    assertNotNull(results);
    assertTrue("expected at least 1 field match", results.size() >= 1);

    for (var match : results) {
      assertNotNull("match uri should not be null", match.getUri());
      assertNotNull("match start should not be null", match.getStart());
      assertNotNull("match end should not be null", match.getEnd());
      assertTrue("start line should be >= 0", match.getStart().getLine() >= 0);
      assertTrue("end line should be >= start line",
          match.getEnd().getLine() >= match.getStart().getLine());
      assertNotNull("matchedText should not be null", match.getMatchedText());
      assertFalse("matchedText should not be empty", match.getMatchedText().isEmpty());
    }
  }

  @Test
  public void testInvalidPatternReturnsEmpty() {
    var results = runSearch("$$invalid $$pattern;;;", null);
    assertNotNull("should return empty list, not null", results);
    assertTrue("invalid pattern should return 0 results", results.isEmpty());
  }

  private List<SemanticMatch> runSearch(String pattern, Map<String, Map<String, String>> constraints) {
    return ApplicationManager.getApplication().runReadAction(
        (com.intellij.openapi.util.Computable<List<SemanticMatch>>) () ->
            SemanticSearchCommand.search(getProject(), pattern, "file", "java",
                testFilePath.toLspUri(), constraints));
  }
}
