package semantic;

import java.util.List;
import java.util.ArrayList;

public class SemanticSearchTest {
    private String name;
    private int count;
    private static final Logger log = new Logger();

    public String getName() {
        return name;
    }

    public int getCount() {
        return count;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void process(List<String> items) {
        for (var item : items) {
            if (item != null) {
                log.record(item);
            }
        }
    }

    static class Logger {
        private final List<String> records = new ArrayList<>();

        public void record(String msg) {
            records.add(msg);
        }
    }
}
