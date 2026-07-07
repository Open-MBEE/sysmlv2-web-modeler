import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class SysMLVizServerFetchBranchesFallbackHarness {

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        String projectId = "proj-fallback";

        server.createContext("/projects/" + projectId + "/branches", exchange -> {
            byte[] body = "{\"error\":\"simulated upstream failure\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        server.createContext("/projects/" + projectId, exchange -> {
            byte[] body = (
                "{"
                    + "\"defaultBranch\":{"
                    + "\"@id\":\"branch-default\","
                    + "\"name\":\"Initial\","
                    + "\"referencedCommit\":{\"@id\":\"commit-default\"}"
                    + "}"
                    + "}"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        server.start();
        try {
            JsonArray branches = SysMLVizServer.fetchBranches(
                "http://localhost:" + port + "/",
                projectId,
                "Bearer test-token"
            );
            if (branches.size() != 1) {
                throw new AssertionError("Expected 1 fallback branch, got " + branches.size());
            }

            JsonObject branch = branches.get(0).getAsJsonObject();
            assertString("fallback branch id", "branch-default", branch.get("id").getAsString());
            assertString("fallback branch name", "Initial", branch.get("name").getAsString());
            assertString("fallback branch commitId", "commit-default", branch.get("commitId").getAsString());
        } finally {
            server.stop(0);
        }
    }

    private static void assertString(String label, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected \"" + expected + "\" but got \"" + actual + "\"");
        }
    }
}
