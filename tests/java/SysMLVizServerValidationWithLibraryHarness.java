import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;

public class SysMLVizServerValidationWithLibraryHarness {

    public static void main(String[] args) {
        SysMLInteractive sysml = SysMLInteractive.createInstance();
        SysMLVizServer.configureLibraries(sysml);

        SysMLInteractiveResult result = sysml.process(
            """
            package pkgA {
              package pkgB {
                package pkgC {
                  package pkgD {
                    part p;
                  }
                }
              }
            }
            """,
            false
        );

        if (result.getException() != null) {
            throw new AssertionError("Unexpected validation exception: " + result.formatException());
        }
        if (result.hasErrors()) {
            throw new AssertionError("Expected library-backed validation to accept part p;");
        }
        if (result.getRootElement() == null) {
            throw new AssertionError("Expected non-null parsed root element");
        }
    }
}
