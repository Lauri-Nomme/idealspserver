package tf.locals.idealsp.server.semantic;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.structuralsearch.DefaultMatchResultSink;
import com.intellij.structuralsearch.MatchOptions;
import com.intellij.structuralsearch.MatchResult;
import com.intellij.structuralsearch.Matcher;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.util.MiscUtil;

import com.intellij.structuralsearch.MatchVariableConstraint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class SemanticSearchCommand {
  private static final Logger LOG = Logger.getInstance(SemanticSearchCommand.class);

  public static @NotNull List<SemanticMatch> search(@NotNull Project project,
                                                      @NotNull String pattern,
                                                      @Nullable String scope,
                                                      @Nullable String language,
                                                      @Nullable String fileUri,
                                                      @Nullable Map<String, Map<String, String>> constraints) {
    var result = new ArrayList<SemanticMatch>();

    var matchOptions = new MatchOptions();
    matchOptions.setSearchPattern(pattern);
    matchOptions.setRecursiveSearch(true);
    matchOptions.setFileType(detectFileType(language));

    if (constraints != null && !constraints.isEmpty()) {
      validateConstraints(constraints);
      applyConstraints(matchOptions, constraints);
    }

    SearchScope resolvedScope = resolveScope(project, scope, fileUri);
    LOG.warn("search: scope=" + resolvedScope.scope + " hasFilter=" + (resolvedScope.fileFilter != null));
    if (resolvedScope.scope != null) {
      matchOptions.setScope(resolvedScope.scope);
    }

    // Debug: log variable names and constraints
    LOG.warn("search: usedVariableNames=" + matchOptions.getUsedVariableNames());
    LOG.warn("search: variableConstraintNames=" + matchOptions.getVariableConstraintNames());
    for (String varName : matchOptions.getVariableConstraintNames()) {
      var vc = matchOptions.getVariableConstraint(varName);
      if (vc != null) {
        LOG.warn("search: constraint[" + varName + "] regex=" + vc.getRegExp() + " invert=" + vc.isInvertRegExp() + " exprType=" + vc.getNameOfExprType());
      }
    }

    try {
      var matcher = new Matcher(project, matchOptions);
      var sink = new DefaultMatchResultSink() {
        @Override
        public void newMatch(@NotNull MatchResult matchResult) {
          var match = matchResult.getMatch();
          if (match == null) return;

          var file = match.getContainingFile();
          if (file == null) return;

          // Apply file filter here (Matcher may call newMatch before processFile filter)
          if (resolvedScope.fileFilter != null) {
            boolean passes = resolvedScope.fileFilter.test(file);
            var vf = file.getVirtualFile();
            String filePath = vf != null ? vf.getPath() : "null-vf";
            LOG.warn("newMatch: file=" + file.getName() + " path=" + filePath + " passes=" + passes);
            if (!passes) return;
          }

          var virtualFile = file.getVirtualFile();
          if (virtualFile == null) return;

          var doc = MiscUtil.getDocument(file);
          if (doc == null) return;

          var uri = LspPath.fromVirtualFile(virtualFile).toLspUri();
          var range = match.getTextRange();
          if (range == null) return;
          var start = MiscUtil.offsetToPosition(doc, range.getStartOffset());
          var end = MiscUtil.offsetToPosition(doc, range.getEndOffset());

          // Log matched text for debugging
          String matchedText = doc.getText(range);
          LOG.warn("newMatch: ADDING uri=" + uri + " line=" + start.getLine() + " text=" + matchedText.replace("\n", " ").substring(0, Math.min(80, matchedText.length())));
          result.add(new SemanticMatch(uri, start, end, matchResult.getMatchImage()));
        }

        @Override
        public void processFile(@NotNull PsiFile file) {
          if (resolvedScope.fileFilter != null && !resolvedScope.fileFilter.test(file)) {
            LOG.warn("processFile: filtered out " + file.getName());
            return;
          }
          LOG.warn("processFile: processing " + file.getName());
          super.processFile(file);
        }
      };

      matcher.findMatches(sink);
      LOG.warn("search: matcher.findMatches completed, result size=" + result.size());
    } catch (IllegalArgumentException e) {
      LOG.error("Semantic search constraint error: " + e.getMessage());
      throw e;
    } catch (Exception e) {
      LOG.warn("Semantic search failed for pattern: " + pattern, e);
    }

    return result;
  }

  private static @NotNull LanguageFileType detectFileType(@Nullable String language) {
    if (language == null || language.equalsIgnoreCase("java")) {
      return com.intellij.openapi.fileTypes.StdFileTypes.JAVA;
    }
    return PlainTextFileType.INSTANCE;
  }

  private static @NotNull SearchScope resolveScope(@NotNull Project project,
                                                     @Nullable String scope,
                                                     @Nullable String fileUri) {
    if (fileUri != null) {
      try {
        var path = LspPath.fromLspUri(fileUri);
        var vFile = path.findVirtualFile();
        if (vFile != null) {
          LOG.warn("resolveScope: found vFile=" + vFile.getPath() + " valid=" + vFile.isValid());
          // Use GlobalSearchScope.fileScope which is designed for single-file searches
          // Must be called inside ReadAction
          var fileScope = ReadAction.compute(() -> GlobalSearchScope.fileScope(project, vFile));
          return new SearchScope(fileScope, null);
        }
      } catch (Exception e) {
        LOG.warn("Failed to resolve file URI: " + fileUri, e);
      }
    }

    if ("file".equalsIgnoreCase(scope) && fileUri != null) {
      return resolveScope(project, null, fileUri);
    }

    return new SearchScope(GlobalSearchScope.projectScope(project), null);
  }

  private record SearchScope(GlobalSearchScope scope, @Nullable Predicate<PsiFile> fileFilter) {}

  static final String SUPPORTED_CONSTRAINTS_HELP =
      "Supported constraint keys: regex, text, notRegex, notText, type, exprType, notType, " +
      "notExprType, formalType, argType, within, contains, context, target, minCount, min, " +
      "maxCount, max, reference, script";

  private static final java.util.Set<String> VALID_KEYS = java.util.Set.of(
      "regex", "text", "notRegex", "notText",
      "type", "exprType", "notType", "notExprType",
      "formalType", "argType",
      "within", "contains", "context", "target",
      "minCount", "min", "maxCount", "max",
      "reference", "script"
  );

  public static void validateConstraints(@NotNull Map<String, Map<String, String>> constraints) {
    for (var entry : constraints.entrySet()) {
      var varName = entry.getKey();
      var varConstraints = entry.getValue();
      if (varConstraints == null) continue;
      for (var prop : varConstraints.keySet()) {
        if (!VALID_KEYS.contains(prop)) {
          throw new IllegalArgumentException(
              "Unknown constraint key: '" + prop + "' for variable '" + varName + "'.\n" +
              SUPPORTED_CONSTRAINTS_HELP);
        }
      }
    }
  }

  private static void applyConstraints(@NotNull MatchOptions matchOptions,
                                         @NotNull Map<String, Map<String, String>> constraints) {
    for (var entry : constraints.entrySet()) {
      var varName = entry.getKey();
      if (varName.startsWith("$") && varName.endsWith("$")) {
        varName = varName.substring(1, varName.length() - 1);
      }
      var varConstraints = entry.getValue();
      if (varConstraints == null || varConstraints.isEmpty()) continue;

      var constraint = matchOptions.addNewVariableConstraint(varName);
      LOG.warn("applyConstraints: varName=" + varName + " constraints=" + varConstraints);
      for (var prop : varConstraints.entrySet()) {
        switch (prop.getKey()) {
          case "regex", "text" -> {
            constraint.setRegExp(prop.getValue());
            constraint.setInvertRegExp(false);
            LOG.warn("applyConstraints: set regex=" + prop.getValue() + " invert=false");
          }
          case "notRegex", "notText" -> {
            constraint.setRegExp(prop.getValue());
            constraint.setInvertRegExp(true);
            LOG.warn("applyConstraints: set regex=" + prop.getValue() + " invert=true");
          }
          case "type", "exprType" -> {
            constraint.setNameOfExprType(prop.getValue());
            constraint.setInvertExprType(false);
            LOG.warn("applyConstraints: set exprType=" + prop.getValue());
          }
          case "notType", "notExprType" -> {
            constraint.setNameOfExprType(prop.getValue());
            constraint.setInvertExprType(true);
            LOG.warn("applyConstraints: set exprType=" + prop.getValue() + " invert=true");
          }
          case "formalType", "argType" -> {
            constraint.setNameOfFormalArgType(prop.getValue());
            constraint.setInvertFormalType(false);
            LOG.warn("applyConstraints: set formalType=" + prop.getValue());
          }
          case "within" -> constraint.setWithinConstraint(prop.getValue());
          case "contains" -> constraint.setContainsConstraint(prop.getValue());
          case "context", "target" -> constraint.setContextConstraint(prop.getValue());
          case "minCount", "min" -> constraint.setMinCount(Integer.parseInt(prop.getValue()));
          case "maxCount", "max" -> constraint.setMaxCount(Integer.parseInt(prop.getValue()));
          case "reference" -> constraint.setReferenceConstraint(prop.getValue());
          case "script" -> constraint.setScriptCodeConstraint(prop.getValue());
        }
      }
    }
  }
}
