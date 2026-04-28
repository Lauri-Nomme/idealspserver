package tf.locals.idealsp.server.dataflow;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class DataFlowLocation {

    private Location location;
    private List<Range> range;
    private @Nullable Object data;

    public DataFlowLocation() {
    }

    public Location getLocation() {
        return location;
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    public List<Range> getRange() {
        return range;
    }

    public void setRange(List<Range> range) {
        this.range = range;
    }

    @Nullable
    public Object getData() {
        return data;
    }

    public void setData(@Nullable Object data) {
        this.data = data;
    }
}