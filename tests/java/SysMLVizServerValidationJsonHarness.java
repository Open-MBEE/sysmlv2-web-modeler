import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;

public class SysMLVizServerValidationJsonHarness {

    public static void main(String[] args) {
        JsonObject nullPayload = SysMLVizServer.validationJson(null);
        if (!nullPayload.has("ok") || nullPayload.get("ok").getAsBoolean()) {
            throw new AssertionError("Expected null validation payload to report ok=false");
        }
        if (!nullPayload.has("issues") || !nullPayload.get("issues").isJsonArray()) {
            throw new AssertionError("Expected issues array on null validation payload");
        }

        SysMLInteractive sysml = SysMLInteractive.createInstance();
        SysMLInteractiveResult success = sysml.process("package pkgA;", false);
        if (success.getException() != null || success.hasErrors()) {
            throw new AssertionError("Expected parser success for validationJson harness");
        }

        JsonObject successPayload = SysMLVizServer.validationJson(success);
        if (!successPayload.get("ok").getAsBoolean()) {
            throw new AssertionError("Expected success payload ok=true");
        }
        if (!successPayload.has("rootElementId")) {
            throw new AssertionError("Expected rootElementId in success payload");
        }
        if (!successPayload.has("issues") || !successPayload.get("issues").isJsonArray()) {
            throw new AssertionError("Expected issues array in success payload");
        }
        JsonArray issues = successPayload.getAsJsonArray("issues");
        if (issues.size() != 0) {
            throw new AssertionError("Expected zero issues for valid package");
        }
    }
}
