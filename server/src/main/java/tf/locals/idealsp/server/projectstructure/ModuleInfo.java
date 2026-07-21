package tf.locals.idealsp.server.projectstructure;

import java.util.List;

public class ModuleInfo {
  private String name;
  private String type;
  private List<String> contentRoots;
  private String sdk;
  private String languageLevel;
  private List<String> facets;
  private List<String> libraryDependencies;

  public ModuleInfo() {}

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public String getType() { return type; }
  public void setType(String type) { this.type = type; }

  public List<String> getContentRoots() { return contentRoots; }
  public void setContentRoots(List<String> contentRoots) { this.contentRoots = contentRoots; }

  public String getSdk() { return sdk; }
  public void setSdk(String sdk) { this.sdk = sdk; }

  public String getLanguageLevel() { return languageLevel; }
  public void setLanguageLevel(String languageLevel) { this.languageLevel = languageLevel; }

  public List<String> getFacets() { return facets; }
  public void setFacets(List<String> facets) { this.facets = facets; }

  public List<String> getLibraryDependencies() { return libraryDependencies; }
  public void setLibraryDependencies(List<String> libraryDependencies) { this.libraryDependencies = libraryDependencies; }
}
