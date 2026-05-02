import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;
import org.omg.sysml.lang.sysml.Element;

public class SysMLVizServerSerializeNodeModelHarness {

    public static void main(String[] args) {
        SysMLInteractive sysml = SysMLInteractive.createInstance();
        SysMLInteractiveResult result = sysml.process(
            """
            package pkgA{
            package pkgB;
            }
            """,
            false
        );

        if (result.getException() != null || result.hasErrors()) {
            throw new AssertionError("Expected parser success for node-model serialization harness");
        }

        Element pkgA = SysMLVizServer.resolveTopLevelLoadedElement(sysml);
        if (pkgA == null) {
            throw new AssertionError("Expected top-level pkgA");
        }

        String text = SysMLVizServer.serializeElementText(pkgA);
        if (text.contains("Fallback SysML text generated")) {
            throw new AssertionError("Expected node-model serialization path, not fallback");
        }
        if (!text.contains("package pkgA")) {
            throw new AssertionError("Expected serialized text to contain pkgA, got: " + text);
        }
        if (!text.contains("package pkgB;")) {
            throw new AssertionError("Expected serialized text to contain pkgB, got: " + text);
        }
    }
}
