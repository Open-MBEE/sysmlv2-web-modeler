import java.util.List;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.VizResult;
import org.omg.sysml.lang.sysml.Element;

/** Uses the same selected-element rendering seam as the HTTP endpoints. */
public class SysMLVizServerRenderFormatsHarness {
  public static void main(String[] args) throws Exception {
    SysMLInteractive sysml = SysMLInteractive.createInstance();
    SysMLVizServer.configureLibraries(sysml);
    var parsed = sysml.process("package Demo { part def Vehicle { part wheel; } }", false);
    if (parsed.hasErrors() || parsed.getException() != null) throw new AssertionError("Parse failed");
    Element vehicle = (Element)SysMLVizServer.resolveLoadedElement(sysml, "Demo::Vehicle", "", "");
    for (String format : List.of("plantuml", "puml", "text", "txt", "svg")) {
      var styles = List.of("LR");
      VizResult result = SysMLVizServer.renderResolvedSelection(sysml, vehicle, List.of("TREE"), styles, format);
      if (result == null || result.hasException()) throw new AssertionError(format + " render failed");
      String output = switch (format) {
        case "plantuml", "puml" -> result.getPlantUML();
        case "text", "txt" -> result.getText();
        default -> result.getSVG();
      };
      String marker = switch (format) {
        case "plantuml", "puml" -> "@startuml";
        case "text", "txt" -> "part def Vehicle";
        default -> "<svg";
      };
      if (output == null || !output.contains(marker)) throw new AssertionError(format + " missing " + marker);
      if (!styles.equals(List.of("LR"))) throw new AssertionError("Caller styles mutated");
      if ((format.equals("text") || format.equals("txt")) && output.contains("package Demo"))
        throw new AssertionError("Expected selected element text, not whole source");
      System.out.println(format + ": PASS");
    }
    var draft = new TextualModelService().renderProcessedModel(
      "package Draft { part def Changed; }", "Draft::Changed", List.of("TREE"), List.of(), "text");
    if (!draft.getText().contains("part def Changed")) throw new AssertionError("Draft format not forwarded");
    System.out.println("draft format: PASS");
  }
}
