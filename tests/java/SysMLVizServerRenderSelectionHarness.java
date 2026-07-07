import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;
import org.omg.sysml.interactive.VizResult;

import java.util.Collections;

public class SysMLVizServerRenderSelectionHarness {

    public static void main(String[] args) {
        SysMLInteractive sysml = SysMLInteractive.createInstance();
        SysMLInteractiveResult result = sysml.process(
            """
            package pkgA {
              package Alpha;
              package Beta;
            }
            """,
            false
        );

        if (result.getException() != null || result.hasErrors()) {
            throw new AssertionError("Expected parser success for render selection harness");
        }

        VizResult alpha = SysMLVizServer.renderLoadedSelection(
            sysml,
            "Alpha",
            "",
            "",
            Collections.emptyList(),
            Collections.emptyList()
        );
        VizResult beta = SysMLVizServer.renderLoadedSelection(
            sysml,
            "Beta",
            "",
            "",
            Collections.emptyList(),
            Collections.emptyList()
        );

        if (alpha == null || alpha.hasException()) {
            throw new AssertionError("Expected Alpha render to succeed");
        }
        if (beta == null || beta.hasException()) {
            throw new AssertionError("Expected Beta render to succeed");
        }

        String alphaOutput = firstNonBlank(alpha.getPlantUML(), alpha.getSVG(), alpha.getText());
        String betaOutput = firstNonBlank(beta.getPlantUML(), beta.getSVG(), beta.getText());
        if (alphaOutput.isBlank()) {
            throw new AssertionError("Expected Alpha render output");
        }
        if (betaOutput.isBlank()) {
            throw new AssertionError("Expected Beta render output");
        }
        if (alphaOutput.equals(betaOutput)) {
            throw new AssertionError("Expected selected element to affect render output");
        }
        if (!alphaOutput.contains("Alpha")) {
            throw new AssertionError("Expected Alpha render output to mention Alpha, got: " + alphaOutput);
        }
        if (!betaOutput.contains("Beta")) {
            throw new AssertionError("Expected Beta render output to mention Beta, got: " + betaOutput);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
