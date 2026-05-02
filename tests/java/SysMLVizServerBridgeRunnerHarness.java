import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SysMLVizServerBridgeRunnerHarness {

    public static void main(String[] args) throws Exception {
        Path tempFile = Files.createTempFile("sysmlviz-bridge-test-", ".txt");
        List<String> failCommand = new ArrayList<>();
        failCommand.add(javaExecutable());
        failCommand.add("-cp");
        failCommand.add(System.getProperty("java.class.path"));
        failCommand.add(SysMLVizServerBridgeRunnerHarness.FailingBridgeProcess.class.getName());

        boolean failedAsExpected = false;
        try {
            SysMLVizServer.runJsonBridgeProcess(
                "[bridge-test]",
                failCommand,
                "Bearer test-token",
                tempFile,
                "Bridge failed",
                "Bridge returned non-object JSON"
            );
        } catch (IllegalStateException ex) {
            failedAsExpected = ex.getMessage() != null && ex.getMessage().contains("Bridge failed");
        }

        if (!failedAsExpected) {
            throw new AssertionError("Expected bridge helper to fail on non-zero exit");
        }
        if (!Files.exists(tempFile)) {
            throw new AssertionError("Expected cleanup file to be preserved on failure");
        }
        Files.deleteIfExists(tempFile);

        List<String> badJsonCommand = new ArrayList<>();
        badJsonCommand.add(javaExecutable());
        badJsonCommand.add("-cp");
        badJsonCommand.add(System.getProperty("java.class.path"));
        badJsonCommand.add(SysMLVizServerBridgeRunnerHarness.NonObjectBridgeProcess.class.getName());

        boolean nonObjectAsExpected = false;
        try {
            SysMLVizServer.runJsonBridgeProcess(
                "[bridge-test]",
                badJsonCommand,
                "Bearer test-token",
                null,
                "Bridge failed",
                "Bridge returned non-object JSON"
            );
        } catch (IllegalStateException ex) {
            nonObjectAsExpected = "Bridge returned non-object JSON".equals(ex.getMessage());
        }

        if (!nonObjectAsExpected) {
            throw new AssertionError("Expected bridge helper to reject non-object JSON");
        }
    }

    private static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        String sep = System.getProperty("file.separator");
        return javaHome + sep + "bin" + sep + "java";
    }

    public static final class FailingBridgeProcess {
        public static void main(String[] args) {
            System.err.print("boom");
            System.exit(7);
        }
    }

    public static final class NonObjectBridgeProcess {
        public static void main(String[] args) {
            System.out.print("[]");
        }
    }
}
