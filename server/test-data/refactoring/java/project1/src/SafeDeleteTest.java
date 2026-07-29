package refactoring;

public class SafeDeleteTest {
    private int unusedField;

    public void usedMethod() {
        System.out.println("used");
    }

    public void unusedMethod() {
        int x = 1;
    }
}
