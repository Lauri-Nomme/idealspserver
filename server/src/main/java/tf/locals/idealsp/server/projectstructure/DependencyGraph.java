package tf.locals.idealsp.server.projectstructure;

import java.util.List;

public class DependencyGraph {
  private List<DependencyEdge> edges;

  public DependencyGraph() {}

  public DependencyGraph(List<DependencyEdge> edges) {
    this.edges = edges;
  }

  public List<DependencyEdge> getEdges() { return edges; }
  public void setEdges(List<DependencyEdge> edges) { this.edges = edges; }
}
