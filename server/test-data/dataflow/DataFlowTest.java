package test;

public class DataFlowTest {
    private String name;
    
    public DataFlowTest(String name) {
        this.name = name;
    }
    
    public void process() {
        System.out.println(name);
    }
    
    public String getName() {
        return name;
    }
    
    public void printName() {
        String formatted = name.toUpperCase();
        System.out.println(formatted);
    }
    
    public static void main(String[] args) {
        DataFlowTest test = new DataFlowTest("hello");
        String result = test.getName();
        System.out.println(result);
    }
}