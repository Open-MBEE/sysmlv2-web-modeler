import org.omg.sysml.interactive.VizResult;

public class SysMLVizServerRenderJsonFeatureChainNpeHarness {

    public static void main(String[] args) {
        NullPointerException npe = brokenFeatureChainNpe();

        // thrown case: exception escapes viz(), normalizeVizException sanitizes it
        VizResult thrownResult = SysMLVizServer.normalizeVizException(npe);
        assertNormalized(thrownResult);

        // wrapped result case: viz() returns exceptionResult, normalizeVizResult sanitizes it
        VizResult wrappedResult = SysMLVizServer.normalizeVizResult(VizResult.exceptionResult(npe));
        assertNormalized(wrappedResult);
    }

    private static void assertNormalized(VizResult result) {
        if (result == null || !result.hasException()) {
            throw new AssertionError("Expected normalized viz exception result");
        }
        String message = result.formatException();
        if (message == null
            || !message.contains("malformed feature chain")
            || !message.contains("getChainingFeature() returned null")) {
            throw new AssertionError("Unexpected normalized message: " + message);
        }
    }

    private static NullPointerException brokenFeatureChainNpe() {
        return new NullPointerException(
            "Cannot invoke \"org.omg.sysml.lang.sysml.Feature.getName()\" because the return value of "
                + "\"org.omg.sysml.lang.sysml.FeatureChaining.getChainingFeature()\" is null"
        );
    }
}
