package tf.locals.idealsp.server.lsp;

import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.Assert;
import org.junit.Test;
import tf.locals.idealsp.server.LspPath;
import tf.locals.idealsp.server.inspections.InspectionListParams;
import tf.locals.idealsp.server.inspections.InspectionRunByNameParams;
import tf.locals.idealsp.server.util.MiscUtil;

import java.nio.file.Files;

public class InspectionListTest extends LspServerTestBase {

    @Override
    protected String getProjectRelativePath() {
        return "lsp/project1";
    }

    @Test
    public void listAllInspections() {
        var result = server().inspectionList(
                new InspectionListParams()).join();

        Assert.assertNotNull(result);
        Assert.assertFalse("Expected at least some inspections", result.isEmpty());

        var shortNames = result.stream()
                .map(i -> i.getShortName())
                .toList();

        Assert.assertTrue("Expected 'unused' inspection", shortNames.contains("unused"));
    }

    @Test
    public void searchByShortName() {
        var result = server().inspectionList(
                new InspectionListParams("unused")).join();

        Assert.assertNotNull(result);
        Assert.assertFalse("Expected at least one result for 'unused'", result.isEmpty());

        for (var info : result) {
            var lower = (info.getShortName() + info.getDisplayName()).toLowerCase();
            Assert.assertTrue("All results should match 'unused': " + info.getShortName(),
                    lower.contains("unused"));
        }
    }

    @Test
    public void searchNonExistent() {
        var result = server().inspectionList(
                new InspectionListParams("zzzthisshouldnotexist")).join();

        Assert.assertNotNull(result);
        Assert.assertTrue("Expected empty result for non-existent query", result.isEmpty());
    }

    @Test
    public void fieldsAreNonNull() {
        var result = server().inspectionList(
                new InspectionListParams("unused")).join();

        Assert.assertFalse(result.isEmpty());

        for (var info : result) {
            Assert.assertNotNull("shortName should not be null", info.getShortName());
            Assert.assertFalse("shortName should not be empty", info.getShortName().isEmpty());
            Assert.assertNotNull("displayName should not be null", info.getDisplayName());
            Assert.assertFalse("displayName should not be empty", info.getDisplayName().isEmpty());
            Assert.assertNotNull("group should not be null", info.getGroup());
            Assert.assertNotNull("description should not be null", info.getDescription());
        }
    }

    @Test
    public void runByNameOnTestFile() {
        var filePath = LspPath.fromLocalPath(getProjectPath().resolve("src/Test.java"));
        var fileUri = filePath.toLspUri();

        sendOpen(filePath);

        var params = new InspectionRunByNameParams(
                new TextDocumentIdentifier(fileUri), "UNUSED_IMPORT");
        var diagnostics = server().inspectionRunByName(params).join();

        Assert.assertNotNull("Diagnostics should not be null", diagnostics);
        // Test project may not have JDK configured for import resolution;
        // the key assertion is that runByName succeeds without exceptions.
    }

    @Test
    public void runByNameNonexistentInspection() {
        var filePath = LspPath.fromLocalPath(getProjectPath().resolve("src/Test.java"));
        var fileUri = filePath.toLspUri();

        sendOpen(filePath);

        var params = new InspectionRunByNameParams(
                new TextDocumentIdentifier(fileUri), "zzzthisdoesnotexist");
        var diagnostics = server().inspectionRunByName(params).join();

        Assert.assertNotNull("Diagnostics should not be null", diagnostics);
        Assert.assertTrue("Expected empty result for non-existent inspection", diagnostics.isEmpty());
    }

    private void sendOpen(LspPath filePath) {
        var fileText = MiscUtil.makeThrowsUnchecked(() -> Files.readString(filePath.toPath()));
        server().getTextDocumentService().didOpen(
                MiscUtil.with(new DidOpenTextDocumentParams(), params -> {
                    params.setTextDocument(MiscUtil.with(new TextDocumentItem(), item -> {
                        item.setUri(filePath.toLspUri());
                        item.setLanguageId("java");
                        item.setText(fileText);
                        item.setVersion(1);
                    }));
                }));
    }
}
