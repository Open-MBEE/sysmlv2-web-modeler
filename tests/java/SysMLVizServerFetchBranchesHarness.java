import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Verifies that fetchBranches() resolves correct branch names by fetching each branch
 * individually, working around a Flexo API bug where GET /projects/{id}/branches returns
 * branch names shuffled across the wrong @id values.
 */
public class SysMLVizServerFetchBranchesHarness {

    // Correct mapping: UUID -> name (ground truth, as returned by detail endpoint)
    private static final Map<String, String> CORRECT_NAMES = Map.of(
        "uuid-default",  "Initial",
        "uuid-branch-a", "feature-a",
        "uuid-branch-b", "feature-b"
    );

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        String projectId = "proj-123";

        // List endpoint: returns names deliberately shuffled (simulates Flexo bug)
        server.createContext("/projects/" + projectId + "/branches", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String branchSegment = path.replaceFirst(".*/branches/?", "");

            byte[] body;
            if (branchSegment.isEmpty()) {
                // List — names are swapped to simulate the Flexo bug
                body = ("[" +
                    "{\"@id\":\"uuid-default\",  \"@type\":\"Branch\",\"name\":\"feature-b\"}," +
                    "{\"@id\":\"uuid-branch-a\", \"@type\":\"Branch\",\"name\":\"Initial\"}," +
                    "{\"@id\":\"uuid-branch-b\", \"@type\":\"Branch\",\"name\":\"feature-a\"}" +
                    "]").getBytes(StandardCharsets.UTF_8);
            } else {
                // Detail — returns the correct name for the requested UUID
                String name = CORRECT_NAMES.getOrDefault(branchSegment, "(unknown)");
                body = ("{\"@id\":\"" + branchSegment + "\",\"@type\":\"Branch\",\"name\":\"" + name + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            }

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        server.start();
        try {
            String apiBase = "http://localhost:" + port + "/";
            JsonArray branches = SysMLVizServer.fetchBranches(apiBase, projectId, "Bearer test-token");

            if (branches.size() != 3) {
                throw new AssertionError("Expected 3 branches, got " + branches.size());
            }

            // Build a name->id map from the result and verify each entry
            java.util.Map<String, String> resultById = new java.util.HashMap<>();
            for (int i = 0; i < branches.size(); i++) {
                JsonObject b = branches.get(i).getAsJsonObject();
                resultById.put(b.get("id").getAsString(), b.get("name").getAsString());
            }

            for (Map.Entry<String, String> expected : CORRECT_NAMES.entrySet()) {
                String actualName = resultById.get(expected.getKey());
                if (!expected.getValue().equals(actualName)) {
                    throw new AssertionError(
                        "Branch " + expected.getKey() + ": expected name=\"" + expected.getValue()
                        + "\" but got \"" + actualName + "\" — list name mismatch not corrected by detail fetch"
                    );
                }
            }
        } finally {
            server.stop(0);
        }
    }
}
