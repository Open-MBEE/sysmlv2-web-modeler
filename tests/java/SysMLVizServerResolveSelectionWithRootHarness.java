import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;
import org.omg.sysml.lang.sysml.Element;

public class SysMLVizServerResolveSelectionWithRootHarness {

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
            throw new AssertionError("Expected parser success for resolve selection with root harness");
        }

        Element root = SysMLVizServer.resolveLoadedElement(sysml, "FlashlightStarterModel");
        if (root == null) {
            throw new AssertionError("Expected root namespace to resolve");
        }

        Element resolved = SysMLVizServer.resolveLoadedElement(
            sysml,
            "FlashlightStarterModel::FlashlightSpecificationAndDesign",
            root.getElementId(),
            "FlashlightStarterModel.sysml"
        );
        if (resolved == null) {
            throw new AssertionError("Expected named descendant to resolve");
        }
        if (!"FlashlightStarterModel::FlashlightSpecificationAndDesign".equals(resolved.getQualifiedName())) {
            throw new AssertionError(
                "Expected named descendant to win over root namespace id, got: " + resolved.getQualifiedName()
            );
        }
    }
}
