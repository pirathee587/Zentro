import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/** Complete archive hashes plus entry content/order/timestamp metadata; JUnit exports omit diagnostic payloads. */
public class ArtifactInventory {
  static String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  static Document parse(Path path) throws Exception {
    var f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
    f.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD","");
    f.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema","");
    return f.newDocumentBuilder().parse(path.toFile());
  }
  static void inventory(Path root,Path output) throws Exception {
    Files.createDirectories(output.resolve("entries"));StringBuilder sums=new StringBuilder();
    try(var paths=Files.walk(root)) {
      for(Path path:paths.filter(p->p.getFileName().toString().endsWith(".jar") && p.getParent().getFileName().toString().equals("target")).sorted().toList()) {
        String relative=root.relativize(path).toString();sums.append(hash(Files.readAllBytes(path))).append("  ").append(relative).append('\n');
        StringBuilder entries=new StringBuilder("order\tname\ttimeMillis\tsize\tmethod\tcrc\textraHex\tcomment\tcontentSHA256\n");
        try(var zip=new ZipFile(path.toFile())) {
          var rows=zip.entries();int index=0;
          while(rows.hasMoreElements()) {
            var entry=rows.nextElement();byte[] content=zip.getInputStream(entry).readAllBytes();
            entries.append(index++).append('\t').append(entry.getName()).append('\t').append(entry.getTime()).append('\t')
                .append(entry.getSize()).append('\t').append(entry.getMethod()).append('\t').append(entry.getCrc()).append('\t')
                .append(entry.getExtra()==null?"":HexFormat.of().formatHex(entry.getExtra())).append('\t')
                .append(Objects.toString(entry.getComment(),"")).append('\t').append(hash(content)).append('\n');
          }
        }
        Files.writeString(output.resolve("entries").resolve(relative.replace('/','_')+".tsv"),entries);
      }
    }
    if(sums.isEmpty())throw new IllegalStateException("No packaged JARs found");
    Files.writeString(output.resolve("sha256.txt"),sums);
  }
  static void sanitize(Path root,Path output) throws Exception {
    Files.createDirectories(output);int files=0;
    try(var paths=Files.walk(root)) {
      for(Path path:paths.filter(p->p.getFileName().toString().endsWith(".xml") && (p.getFileName().toString().startsWith("TEST-") || p.getFileName().toString().equals("vitest.xml"))).sorted().toList()) {
        Document source=parse(path),dest=DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element suites=dest.createElement("testsuites");dest.appendChild(suites);
        NodeList originals=source.getElementsByTagName("testsuite");
        for(int i=0;i<originals.getLength();i++) {
          Element original=(Element)originals.item(i),suite=dest.createElement("testsuite");
          for(String key:List.of("name","tests","failures","errors","skipped","time"))if(original.hasAttribute(key))suite.setAttribute(key,original.getAttribute(key));
          suites.appendChild(suite);
          for(Node n=original.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element testcase && testcase.getTagName().equals("testcase")) {
            Element row=dest.createElement("testcase");
            for(String key:List.of("name","classname","time"))if(testcase.hasAttribute(key))row.setAttribute(key,testcase.getAttribute(key));
            for(Node c=testcase.getFirstChild();c!=null;c=c.getNextSibling())if(c instanceof Element status && List.of("failure","error","skipped").contains(status.getTagName())) {
              Element marker=dest.createElement(status.getTagName());marker.setAttribute("message","Diagnostic payload omitted from public artifact");row.appendChild(marker);
            }
            suite.appendChild(row);
          }
        }
        var transform=TransformerFactory.newInstance().newTransformer();
        transform.transform(new DOMSource(dest),new StreamResult(output.resolve(String.format("%04d.xml",files++)).toFile()));
      }
    }
    System.out.println("Sanitized JUnit files: "+files+"; properties/stdout/stderr/failure payloads omitted");
  }
  public static void main(String[] args) throws Exception {
    if(args.length!=3)throw new IllegalArgumentException("Usage: inventory|sanitize INPUT OUTPUT");
    if(args[0].equals("inventory"))inventory(Path.of(args[1]),Path.of(args[2]));
    else if(args[0].equals("sanitize"))sanitize(Path.of(args[1]),Path.of(args[2]));
    else throw new IllegalArgumentException("Unknown command");
  }
}
