import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SysMLVizServerFlashlightHarness {

    public static void main(String[] args) throws Exception {
        Path modelPath = Files.exists(Path.of("tests/flashlight.sysml"))
            ? Path.of("tests/flashlight.sysml")
            : Paths.get("tests/Flashlight.sysml");
        String modelText = Files.readString(modelPath);

        TextualModelService service = new TextualModelService();
        JsonObject result = service.validateModel("[flashlight-test]", modelText);

        boolean ok = result.get("ok").getAsBoolean();
        boolean hasErrors = result.get("hasErrors").getAsBoolean();
        boolean hasWarnings = result.get("hasWarnings").getAsBoolean();

        System.out.println("ok=" + ok + " hasErrors=" + hasErrors + " hasWarnings=" + hasWarnings);

        if (hasErrors) {
            System.out.println("Errors: " + result.get("syntaxErrors"));
            System.out.println("Semantic errors: " + result.get("semanticErrors"));
            throw new AssertionError("Flashlight model validation reported errors: " + result);
        }

        if (!ok) {
            throw new AssertionError("Flashlight model validation returned ok=false without errors: " + result);
        }
    }
}
