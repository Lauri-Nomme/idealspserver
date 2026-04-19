# IntelliJ Completion - Implementation Research

## Key Classes

### 1. CompletionService (intellij.platform.analysis.jar)
```java
public abstract class CompletionService {
  public static CompletionService getCompletionService();
  public abstract CompletionResultSet createResultSet(
    CompletionParameters params,
    Consumer<CompletionResult> consumer,
    CompletionContributor contributor,
    PrefixMatcher matcher
  );
  public void performCompletion(CompletionParameters params, Consumer<CompletionResult> consumer);
  public void getVariantsFromContributors(CompletionParameters params, ...);
}
```

### 2. BaseCompletionService (intellij.platform.analysis.impl.jar)
```java
public class BaseCompletionService extends CompletionService {
  public void performCompletion(CompletionParameters params, Consumer<CompletionResult> consumer);
  public CompletionResultSet createResultSet(...);
}
```

### 3. CompletionParameters (intellij.platform.analysis.jar)
```java
public class CompletionParameters extends BaseCompletionParameters {
  public PsiFile getFile();
  public Editor getEditor();
  public int getOffset();
  public CompletionType getCompletionType();
  public String getPrefix();
}
```

### 4. CompletionResultSet (intellij.platform.analysis.jar)
```java
public abstract class CompletionResultSet implements Consumer<LookupElement> {
  public abstract void addElement(LookupElement element);
  public abstract CompletionResultSet withPrefixMatcher(PrefixMatcher matcher);
  public abstract CompletionResultSet withRelevanceSorter(CompletionSorter sorter);
  public abstract void stopHere();
  public abstract boolean isStopped();
}
```

### 5. CompletionContributor (intellij.platform.analysis.jar)
```java
public abstract class CompletionContributor {
  public abstract void fillCompletionVariants(CompletionParameters params, CompletionResultSet resultSet);
}
```

Extension point: `CompletionContributorEP` (com.intellij.completion.contributor)

### 6. CompletionType (intellij.platform.analysis.jar)
```java
public enum CompletionType {
  BASIC,
  SMART,
  CLASS_INNER,
  IMPORTS
}
```

### 7. PrefixMatcher (intellij.platform.analysis.jar)
```java
public abstract class PrefixMatcher {
  public abstract boolean prefixMatches(String name);
  public abstract PrefixMatcher cloneWithPrefix(String prefix);
  public abstract String getPrefix();
}
```

Use `PlainPrefixMatcher` for simple prefix matching.

## How IDE Invokes Completion

### From EditorAction (e.g., StartCompletionAction):

1. Get `CompletionParameters`:
```java
CompletionParameters params = new CompletionParameters(
  file,
  prefix,
  CompletionType.BASIC,
  editor,
  offset
);
```

2. Get completion service:
```java
CompletionService service = CompletionService.getCompletionService();
```

3. Call performCompletion:
```java
service.performCompletion(parameters, resultConsumer);
```

4. Inside BaseCompletionService.performCompletion():
   - Creates resultSet via `createResultSet()`
   - Gets contributors from EP
   - For each contributor: `contributor.fillCompletionVariants(params, resultSet)`
   - Results collected via consumer

## Implementation Approach

### Option 1: Direct Contributor Invocation

```java
CompletionParameters params = new CompletionParameters(
  psiFile,
  prefix,  // get from document around offset
  CompletionType.BASIC,
  editor,
  offset
);

CompletionService service = CompletionService.getCompletionService();
CompletionResultSet resultSet = service.createResultSet(
  params,
  result -> elements.add(result.getLookupElement()),
  null,  // no specific contributor - runs all
  new PlainPrefixMatcher(prefix)
);

service.performCompletion(params, result -> {
  // collect results
});
```

### Option 2: EP Direct Access

```java
ExtensionPoint<CompletionContributorEP> ep = ExtensionPointName.create("com.intellij.completion.contributor");
for (CompletionContributorEP ext : ep.getExtensions()) {
  CompletionContributor contributor = ext.getExtension();
  CompletionResultSet resultSet = CompletionService.getCompletionService()
    .createResultSet(parameters, consumer, contributor, matcher);
  contributor.fillCompletionVariants(parameters, resultSet);
}
```

### Option 3: Create Parameters via CompletionInitializationUtil

In SDK 2026.1:
```java
CompletionInitializationContext initContext = CompletionInitializationUtil.createCompletionInitializationContext(
  project, editor, caret, 1, CompletionType.BASIC);

CompletionParameters params = CompletionInitializationUtil.createCompletionParameters(
  initContext, process, offsetsInFile);
```

## Required Imports

```java
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
```

## Testing Notes

- Test file: `java-function-and-keyword-project/src/function_and_keyword.java`
- Contains class with method `formula()` 
- Test expects that method to appear in completion results
- Need real completion, not fallback keywords