import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Verifies that fetchBranches() accepts branch collections wrapped in an "items" array,
 * which some standalone deployments return instead of a top-level array or "elements".
 */
public class SysMLVizServerFetchBranchesItemsHarness {

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        String projectId = "proj-items";

        server.createContext("/projects/" + projectId + "/branches", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String branchSegment = path.replaceFirst(".*/branches/?", "");

            byte[] body;
            if (branchSegment.isEmpty()) {
                body = ("{" +
                    "\"items\":[" +
                    "{\"@id\":\"branch-1\",\"name\":\"wrong-name\"}," +
                    "{\"@id\":\"branch-2\",\"name\":\"also-wrong\"}" +
                    "]" +
                    "}").getBytes(StandardCharsets.UTF_8);
            } else if ("branch-1".equals(branchSegment)) {
                body = "{\"@id\":\"branch-1\",\"name\":\"Initial\",\"head\":{\"@id\":\"commit-1\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            } else if ("branch-2".equals(branchSegment)) {
                body = "{\"@id\":\"branch-2\",\"name\":\"feature-a\",\"referencedCommit\":{\"@id\":\"commit-2\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            } else {
                body = "{}".getBytes(StandardCharsets.UTF_8);
            }

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        server.start();
        try {
            String apiBase = "http://localhost:" + port + "/";
            JsonArray branches = SysMLVizServer.fetchBranches(apiBase, projectId, "Bearer test-token");

            if (branches.size() != 2) {
                throw new AssertionError("Expected 2 branches, got " + branches.size());
            }

            JsonObject first = branches.get(0).getAsJsonObject();
            JsonObject second = branches.get(1).getAsJsonObject();

            if (!"branch-1".equals(first.get("id").getAsString())
                || !"Initial".equals(first.get("name").getAsString())
                || !"commit-1".equals(first.get("commitId").getAsString())) {
                throw new AssertionError("First branch was not normalized from items/detail correctly: " + first);
            }

            if (!"branch-2".equals(second.get("id").getAsString())
                || !"feature-a".equals(second.get("name").getAsString())
                || !"commit-2".equals(second.get("commitId").getAsString())) {
                throw new AssertionError("Second branch was not normalized from items/detail correctly: " + second);
            }
        } finally {
            server.stop(0);
        }
    }
}
