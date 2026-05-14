package tf.locals.idealsp.server.semantic;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(JUnit4.class)
public class SemanticSearchCommandTest {

  @Test
  public void testValidateConstraintsValidKeys() {
    var constraints = new HashMap<String, Map<String, String>>();
    constraints.put("$ReturnType$", Map.of("regex", "Optional"));
    constraints.put("$MethodName$", Map.of("text", "get.*"));
    constraints.put("$Param$", Map.of("type", "java.lang.String"));
    var expr = new HashMap<String, String>();
    expr.put("min", "1");
    expr.put("max", "3");
    constraints.put("$Expr$", expr);
    SemanticSearchCommand.validateConstraints(constraints);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testValidateConstraintsRejectsUnknownKey() {
    try {
      SemanticSearchCommand.validateConstraints(Map.of("$Type$", Map.of("foo", "bar")));
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("foo"));
      assertTrue(e.getMessage().contains(SemanticSearchCommand.SUPPORTED_CONSTRAINTS_HELP));
      throw e;
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testValidateConstraintsRejectsUnknownKeyOnSecondVariable() {
    try {
      var c = new HashMap<String, Map<String, String>>();
      c.put("$Type$", Map.of("regex", "Logger"));
      c.put("$Field$", Map.of("bogus", "val"));
      SemanticSearchCommand.validateConstraints(c);
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("bogus"));
      assertTrue(e.getMessage().contains(SemanticSearchCommand.SUPPORTED_CONSTRAINTS_HELP));
      throw e;
    }
  }

  @Test
  public void testValidateConstraintsAllSupportedKeys() {
    var v1 = new HashMap<String, String>();
    v1.put("regex", "foo");
    v1.put("text", "bar");
    v1.put("notRegex", "baz");
    v1.put("notText", "qux");
    v1.put("type", "java.lang.String");
    v1.put("exprType", "int");
    v1.put("notType", "void");
    v1.put("notExprType", "boolean");
    v1.put("formalType", "String");
    v1.put("argType", "int");
    v1.put("within", "class");
    v1.put("contains", "method");
    v1.put("context", "target");
    v1.put("target", "field");
    v1.put("minCount", "1");
    v1.put("min", "0");
    v1.put("maxCount", "5");
    v1.put("max", "10");
    v1.put("reference", "someRef");
    v1.put("script", "groovy code");
    SemanticSearchCommand.validateConstraints(Map.of("$V1$", v1));
  }

  @Test
  public void testValidateConstraintsEmptyEntry() {
    SemanticSearchCommand.validateConstraints(Map.of("$X$", Map.of()));
  }

  @Test
  public void testValidateConstraintsNullValue() {
    var c = new HashMap<String, Map<String, String>>();
    c.put("$X$", null);
    SemanticSearchCommand.validateConstraints(c);
  }
}
