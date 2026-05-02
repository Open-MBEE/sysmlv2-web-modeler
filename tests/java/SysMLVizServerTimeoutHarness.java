public class SysMLVizServerTimeoutHarness {

    public static void main(String[] args) throws Exception {
        boolean timedOut = false;
        try {
            SysMLVizServer.runWithTimeout("timeout harness", () -> {
                Thread.sleep(200L);
                return "done";
            }, 25L);
        } catch (SysMLVizServer.RenderTimeoutException ex) {
            timedOut = true;
            String message = ex.getMessage();
            if (message == null || !message.contains("timeout harness timed out after 25 ms")) {
                throw new AssertionError("Unexpected timeout message: " + message);
            }
        }

        if (!timedOut) {
            throw new AssertionError("Expected RenderTimeoutException");
        }
    }
}
