import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Verifies that TextualModelService.exportParsedJson() returns a non-empty
 * DataVersion JSON array for a valid model, and throws IllegalArgumentException
 * for a model with parse errors.
 */
public class SysMLVizServerExportParsedJsonHarness {

    public static void main(String[] args) throws Exception {
        TextualModelService service = new TextualModelService();

        // Valid model: should produce a non-empty array of DataVersion objects
        String modelText = "package pkgA { part p; }";
        JsonArray payload = service.exportParsedJson(modelText);

        if (payload == null) {
            throw new AssertionError("exportParsedJson returned null for valid model");
        }
        if (payload.size() == 0) {
            throw new AssertionError("exportParsedJson returned empty array for valid model");
        }
        for (int i = 0; i < payload.size(); i++) {
            JsonElement entry = payload.get(i);
            if (!entry.isJsonObject()) {
                throw new AssertionError(
                    "Expected each element in payload to be a JsonObject at index " + i
                    + ", got: " + entry.getClass().getSimpleName());
            }
        }

        // Model with parse errors: should throw IllegalArgumentException
        boolean threw = false;
        try {
            service.exportParsedJson("this is not valid $$$sysml");
            threw = false;
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        if (!threw) {
            throw new AssertionError("Expected IllegalArgumentException for invalid SysML model");
        }
    }
}
