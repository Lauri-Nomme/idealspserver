package org.rri.ideals.server.completions;

import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.rri.ideals.server.LspLightBasePlatformTestCase;
import org.rri.ideals.server.LspPath;
import org.rri.ideals.server.TestUtil;

import java.util.List;

import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class BasicCompletionServiceTest extends LspLightBasePlatformTestCase {

    @Test
    public void testBasicCompletionReturnsItems() {
        // Create a simple Java file
        String javaCode = "public class Test {\n" +
                         "    public static void main(String[] args) {\n" +
                         "        String s = \"\";\n" +
                         "        s.|  // completion position\n" +
                         "    }\n" +
                         "}";
        
        PsiFile psiFile = myFixture.configureByText(StdFileTypes.JAVA, javaCode);
        
        // Get completion service
        CompletionService completionService = getProject().getService(CompletionService.class);
        
        // Create position at the completion point (after "s.")
        Position position = new Position(3, 6);
        
        // Compute completions
        List<?> completionItems = completionService.computeCompletions(
            LspPath.fromVirtualFile(psiFile.getVirtualFile()), 
            position, 
            new TestUtil.DumbCancelChecker()
        );
        
        // Verify that we get some completion items (basic functionality check)
        assertTrue("Completion should return some items", completionItems.size() > 0);
        
        // Log the results for debugging
        System.out.println("Found " + completionItems.size() + " completion items:");
        completionItems.forEach(item -> {
            System.out.println("  - " + item);
        });
    }
    
    @Test
    public void testBasicCompletionWithFallback() {
        // Test with an empty file to trigger fallback
        String javaCode = "public class Test {\n" +
                         "    public void method() {\n" +
                         "        |\n" +
                         "    }\n" +
                         "}";
        
        PsiFile psiFile = myFixture.configureByText(StdFileTypes.JAVA, javaCode);
        
        // Get completion service
        CompletionService completionService = getProject().getService(CompletionService.class);
        
        // Create position at the completion point
        Position position = new Position(2, 8);
        
        // Compute completions
        List<?> completionItems = completionService.computeCompletions(
            LspPath.fromVirtualFile(psiFile.getVirtualFile()), 
            position, 
            new TestUtil.DumbCancelChecker()
        );
        
        // Verify that we get some completion items (fallback should work)
        assertTrue("Completion should return fallback items", completionItems.size() > 0);
    }
}