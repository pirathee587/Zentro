import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Java 21 source launcher: no Maven plugin or third-party parser needed. Never reads environment files. */
public class BuildVerification {
  static Document xml(Path path) throws Exception {
    var factory=DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
    return factory.newDocumentBuilder().parse(path.toFile());
  }
  static Element child(Element parent,String name) {
    for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())
      if(n instanceof Element e && e.getTagName().equals(name))return e;
    return null;
  }
  static String value(Element parent,String name) {
    Element element=child(parent,name);return element==null?null:element.getTextContent().trim();
  }
  static void require(boolean condition,String message) { if(!condition)throw new IllegalArgumentException(message); }
  static String versions(Path root,boolean release) throws Exception {
    Element project=xml(root.resolve("backend/pom.xml")).getDocumentElement();
    String version=value(project,"version");require(version!=null,"Parent must declare its project version");
    List<Path> poms=new ArrayList<>(List.of(root.resolve("backend/pom.xml")));
    Element modules=child(project,"modules");
    if(modules!=null)for(Node n=modules.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element module) {
      Path path=root.resolve("backend").resolve(module.getTextContent().trim()).resolve("pom.xml").normalize();
      require(path.startsWith(root),"Module path leaves repository");poms.add(path);
    }
    for(Path path:poms) {
      Element module=xml(path).getDocumentElement();
      if(!path.equals(poms.get(0))) {
        Element parent=child(module,"parent");
        require(parent!=null && version.equals(value(parent,"version")),"Parent version drift: "+root.relativize(path));
        String own=value(module,"version");require(own==null || version.equals(own),"Module version drift: "+root.relativize(path));
      }
      if(release) {
        NodeList declared=module.getElementsByTagName("version");
        for(int i=0;i<declared.getLength();i++)require(!declared.item(i).getTextContent().contains("SNAPSHOT"),
            "SNAPSHOT forbidden in release: "+root.relativize(path));
      }
    }
    return version;
  }
  static void release(Path root,Map<String,String> options) throws Exception {
    String version=versions(root,true);
    require("semver".equals(options.get("--scheme")),"Explicit supported policy required: --scheme semver");
    require(options.containsKey("--prefix"),"Explicit --prefix required; no repository tag policy is assumed");
    boolean pre=Boolean.parseBoolean(options.getOrDefault("--allow-prerelease","false"));
    String pattern="(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)";
    if(pre)pattern+="(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?";
    require(version.matches(pattern),"Version does not match explicitly supplied policy");
    String expected=options.get("--prefix")+version;
    require(expected.equals(options.get("--tag")),"Tag must equal the explicit prefix plus project version");
    System.out.println("OK release metadata for supplied policy; no tag/release operation performed");
  }
  static void workflows(Path root,boolean strict) throws Exception {
    Path directory=root.resolve(".github/workflows");
    require(Files.isDirectory(directory),"Workflow directory missing");
    try(var paths=Files.list(directory)) {
      for(Path path:paths.sorted().toList()) {
        if(!path.toString().matches(".*\\.ya?ml$"))continue;
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"Workflow symlinks are not accepted");
        List<String> lines=Files.readAllLines(path);
        boolean target=false,checkout=false,inRun=false;int runIndent=-1;
        for(int i=0;i<lines.size();i++) {
          String line=lines.get(i),trim=line.stripLeading();
          if(trim.isBlank() || trim.startsWith("#"))continue;
          int indent=line.length()-trim.length();
          if(inRun && indent<=runIndent)inRun=false;
          if(trim.matches("(?:-\\s+)?run:.*")) {inRun=true;runIndent=indent;}
          String where=root.relativize(path)+":"+(i+1);
          require(!trim.contains("continue-on-error:") || trim.matches("continue-on-error:\\s*false(?:\\s*#.*)?$"),"Failure masking at "+where);
          if(inRun)require(!Pattern.compile("\\|\\|\\s*(true|:)(?:\\s|$|;)").matcher(trim).find(),"Shell failure masking at "+where);
          if(Pattern.compile("\\bpull_request_target\\b").matcher(trim).find())target=true;
          var use=Pattern.compile("(?:-\\s*)?uses:\\s*([^ #]+)").matcher(trim);
          if(use.find()) {
            String action=use.group(1);
            checkout |= action.startsWith("actions/checkout@");
            if(!action.startsWith("./") && !action.matches("[^@]+@[0-9a-fA-F]{40}")) {
              require(!strict,"Mutable action ref at "+where);
              System.out.println("RISK mutable action ref at "+where);
            }
          }
          if(inRun)require(!trim.matches(".*\\$\\{\\{.*github\\.event\\.(pull_request|issue)\\.(title|body).*"),
              "Untrusted event text interpolated into shell at "+where);
        }
        require(!(target&&checkout),"pull_request_target with checkout: "+root.relativize(path));
      }
    }
    System.out.println("OK targeted workflow security guards (not a YAML/actionlint replacement)");
  }
  public static void main(String[] args) throws Exception {
    try {
      require(args.length>=2,"Usage: BuildVerification.java check|versions|release|version|workflows ROOT [policy options]");
      Path root=Path.of(args[1]).toAbsolutePath().normalize();
      Map<String,String> options=new HashMap<>();
      for(int i=2;i<args.length;i++) {
        if(args[i].equals("--strict-pins"))options.put(args[i],"true");
        else {require(i+1<args.length,"Missing option value");String key=args[i];options.put(key,args[++i]);}
      }
      switch(args[0]) {
        case "frontend-version" -> {
          var match=Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(Files.readString(root.resolve("frontend/zentro-web/package.json")));
          require(match.find(),"Frontend version missing");System.out.println(match.group(1));
        }
        case "version" -> System.out.println(versions(root,false));
        case "versions" -> System.out.println("OK backend module versions: "+versions(root,false));
        case "release" -> release(root,options);
        case "workflows" -> workflows(root,options.containsKey("--strict-pins"));
        case "check" -> {System.out.println("OK backend module versions: "+versions(root,false));workflows(root,options.containsKey("--strict-pins"));}
        default -> throw new IllegalArgumentException("Unknown command");
      }
    } catch(IllegalArgumentException failure) {System.err.println("FAIL: "+failure.getMessage());System.exit(1);}
  }
}
