package tf.locals.idealsp.server.projectstructure;

public class EntryPoint {
  private String kind;
  private String name;
  private String qualifiedName;
  private String file;
  private int line;
  private String module;

  public EntryPoint() {}

  public String getKind() { return kind; }
  public void setKind(String kind) { this.kind = kind; }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public String getQualifiedName() { return qualifiedName; }
  public void setQualifiedName(String qualifiedName) { this.qualifiedName = qualifiedName; }

  public String getFile() { return file; }
  public void setFile(String file) { this.file = file; }

  public int getLine() { return line; }
  public void setLine(int line) { this.line = line; }

  public String getModule() { return module; }
  public void setModule(String module) { this.module = module; }
}
