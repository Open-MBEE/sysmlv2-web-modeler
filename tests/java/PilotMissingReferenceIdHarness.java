import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.emf.ecore.EcoreFactory;
import org.omg.sysml.util.repository.APIModel;
import org.omg.sysml.util.repository.EMFModelRefresher;
import org.omg.sysml.util.repository.EObjectUUIDTracker;

public class PilotMissingReferenceIdHarness {
    public static void main(String[] args) throws Exception {
        var tracker = new EObjectUUIDTracker();
        var targetId = UUID.randomUUID();
        var target = EcoreFactory.eINSTANCE.createEClass();
        tracker.trackLocalElement(targetId, target);

        var refresher = new EMFModelRefresher(new APIModel(Map.of(), Map.of()), tracker);
        var resolve = EMFModelRefresher.class.getDeclaredMethod("resolveReference", Object.class, boolean.class);
        resolve.setAccessible(true);

        var unresolved = resolve.invoke(refresher, Map.of("@ref", "PartUsage"), false);
        if (!Optional.empty().equals(unresolved) || refresher.getIssues().isEmpty()) {
            throw new AssertionError("A reference without @id should be unresolved and recorded as an issue");
        }

        var resolved = resolve.invoke(refresher, Map.of("@id", targetId.toString()), false);
        if (!Optional.of(target).equals(resolved)) {
            throw new AssertionError("A valid @id should still resolve to its tracked element");
        }
    }
}
