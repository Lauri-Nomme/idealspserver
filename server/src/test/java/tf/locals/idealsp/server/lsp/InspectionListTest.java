package tf.locals.idealsp.server.lsp;

import org.junit.Assert;
import org.junit.Test;
import tf.locals.idealsp.server.inspections.InspectionListParams;

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
}
