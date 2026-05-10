import java.util.List;
import java.util.ArrayList;

class UnusedImportFixture {
    public void test() {
        List<String> l = new ArrayList<>();
    }
    
    public void unusedMethod() {
        // This method is never used
    }
}
