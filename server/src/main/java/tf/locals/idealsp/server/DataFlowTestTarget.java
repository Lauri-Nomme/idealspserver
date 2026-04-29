package tf.locals.idealsp.server;

public class DataFlowTestTarget {
    private String inputValue;
    private String processedValue;
    private String resultValue;
    
    public DataFlowTestTarget(String input) {
        this.inputValue = input;
    }
    
    public void process() {
        String temp = transform(inputValue);
        processedValue = temp;
        String finalResult = additionalTransform(temp);
        resultValue = finalResult;
    }
    
    private String transform(String value) {
        if (value == null) {
            return "default";
        }
        return value.toUpperCase();
    }
    
    private String additionalTransform(String value) {
        return value + "_PROCESSED";
    }
    
    public String getResult() {
        return resultValue;
    }
    
    public String getInput() {
        return inputValue;
    }
    
    public static void main(String[] args) {
        DataFlowTestTarget target = new DataFlowTestTarget("hello");
        target.process();
        System.out.println(target.getResult());
    }
}