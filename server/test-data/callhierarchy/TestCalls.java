package callhierarchy;

public class TestCalls {
    private String name;

    public TestCalls(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

    public void printName() {
        System.out.println(getName());
    }

    public void process() {
        String n = getName();
        printName();
        new TestCalls("test");
    }

    public static void main(String[] args) {
        TestCalls tc = new TestCalls("hello");
        tc.process();
    }
}
