import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;
import org.omg.sysml.lang.sysml.Element;

public class SysMLVizServerResolveRootNamespaceFallbackHarness {

    public static void main(String[] args) {
        SysMLInteractive sysml = SysMLInteractive.createInstance();
        SysMLInteractiveResult result = sysml.process(
            """
            package FlashlightStarterModel {
              package FlashlightSpecificationAndDesign {
                package Requirements;
              }
            }
            """,
            false
        );

        if (result.getException() != null || result.hasErrors()) {
            throw new AssertionError("Expected parser success for root namespace fallback harness");
        }

        Element root = SysMLVizServer.resolveLoadedElement(sysml, "FlashlightStarterModel");
        if (root == null) {
            throw new AssertionError("Expected root package to resolve");
        }

        Element resolvedByRootOnly = SysMLVizServer.resolveLoadedElement(
            sysml,
            "",
            root.getElementId(),
            "FlashlightStarterModel.sysml"
        );
        if (resolvedByRootOnly == null) {
            throw new AssertionError("Expected root-namespace-only resolution to succeed");
        }
        if (!"FlashlightStarterModel".equals(resolvedByRootOnly.getQualifiedName())) {
            throw new AssertionError(
                "Expected root namespace fallback to resolve FlashlightStarterModel, got: "
                    + resolvedByRootOnly.getQualifiedName()
            );
        }
    }
}
