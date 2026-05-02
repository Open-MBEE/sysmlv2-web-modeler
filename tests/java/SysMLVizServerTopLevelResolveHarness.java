import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;
import org.omg.sysml.lang.sysml.Element;

public class SysMLVizServerTopLevelResolveHarness {

    public static void main(String[] args) {
        SysMLInteractive sysml = SysMLInteractive.createInstance();
        SysMLInteractiveResult result = sysml.process(
            """
            package pkgA {
              package pkgB;
            }
            """,
            false
        );

        if (result.getException() != null || result.hasErrors()) {
            throw new AssertionError("Expected parser success for top-level resolution harness");
        }

        Element resolved = SysMLVizServer.resolveTopLevelLoadedElement(sysml);
        if (resolved == null) {
            throw new AssertionError("Expected a resolved top-level element");
        }
        String qualifiedName = resolved.getQualifiedName();
        if (!"pkgA".equals(qualifiedName)) {
            throw new AssertionError("Expected pkgA as top-level element, got: " + qualifiedName);
        }
    }
}
