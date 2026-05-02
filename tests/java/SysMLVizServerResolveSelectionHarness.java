import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;
import org.omg.sysml.lang.sysml.Element;

public class SysMLVizServerResolveSelectionHarness {

    public static void main(String[] args) {
        SysMLInteractive sysml = SysMLInteractive.createInstance();
        SysMLInteractiveResult result = sysml.process(
            """
            package pkgA {
              package pkgB {
                package pkgA;
              }
            }
            """,
            false
        );

        if (result.getException() != null || result.hasErrors()) {
            throw new AssertionError("Expected parser success for resolve selection harness");
        }

        Element resolved = SysMLVizServer.resolveLoadedElement(sysml, "pkgA");
        if (resolved == null) {
            throw new AssertionError("Expected pkgA to resolve");
        }
        if (!"pkgA".equals(resolved.getQualifiedName())) {
            throw new AssertionError("Expected top-level pkgA to win selection, got: " + resolved.getQualifiedName());
        }
    }
}
