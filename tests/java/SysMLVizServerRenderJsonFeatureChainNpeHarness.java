import org.omg.sysml.interactive.VizResult;

public class SysMLVizServerRenderJsonFeatureChainNpeHarness {

    public static void main(String[] args) {
        NullPointerException npe = brokenFeatureChainNpe();
        NullPointerException metadataNpe = metadataFeatureChainNpe();

        // thrown case: exception escapes viz(), normalizeVizException sanitizes it
        VizResult thrownResult = SysMLVizServer.normalizeVizException(npe);
        assertNormalized(thrownResult);

        // wrapped result case: viz() returns exceptionResult, normalizeVizResult sanitizes it
        VizResult wrappedResult = SysMLVizServer.normalizeVizResult(VizResult.exceptionResult(npe));
        assertNormalized(wrappedResult);

        VizResult metadataThrownResult = SysMLVizServer.normalizeVizException(metadataNpe);
        assertMetadataNormalized(metadataThrownResult);

        VizResult metadataWrappedResult = SysMLVizServer.normalizeVizResult(VizResult.exceptionResult(metadataNpe));
        assertMetadataNormalized(metadataWrappedResult);
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

    private static void assertMetadataNormalized(VizResult result) {
        if (result == null || !result.hasException()) {
            throw new AssertionError("Expected normalized metadata viz exception result");
        }
        String message = result.formatException();
        if (message == null
            || !message.contains("malformed metadata feature chain")
            || !message.contains("HIDEMETADATA")) {
            throw new AssertionError("Unexpected normalized metadata message: " + message);
        }
    }

    private static NullPointerException metadataFeatureChainNpe() {
        return new NullPointerException(
            "Cannot invoke \"org.omg.sysml.lang.sysml.Feature.getOwnedFeatureChaining()\" because \"sf\" is null"
        );
    }
}
