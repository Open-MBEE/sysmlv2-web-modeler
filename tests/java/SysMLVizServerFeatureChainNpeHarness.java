import org.omg.sysml.interactive.VizResult;

public class SysMLVizServerFeatureChainNpeHarness {

    public static void main(String[] args) {
        NullPointerException npe = new NullPointerException(
            "Cannot invoke \"org.omg.sysml.lang.sysml.Feature.getName()\" because the return value of "
                + "\"org.omg.sysml.lang.sysml.FeatureChaining.getChainingFeature()\" is null"
        );

        VizResult result = SysMLVizServer.normalizeVizException(npe);

        if (!result.hasException()) {
            throw new AssertionError("Expected viz exception result for malformed feature chain");
        }

        String message = result.formatException();
        if (message == null
            || !message.contains("malformed feature chain")
            || !message.contains("getChainingFeature() returned null")) {
            throw new AssertionError("Unexpected viz exception message: " + message);
        }
    }
}
