import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public class SysMLVizServerFetchProjectsHarness {

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/projects", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (
                "{\"projects\":[" +
                "{\"@id\":\"proj-1\",\"name\":\"Flashlight\"}," +
                "{\"id\":\"proj-2\"}" +
                "]}"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        server.start();
        try {
            String apiBase = "http://localhost:" + port + "/";
            JsonArray projects = SysMLVizServer.fetchProjects(apiBase, "Bearer test-token");
            assertString("forwarded authorization", "Bearer test-token", authorization.get());

            JsonArray anonymousProjects = SysMLVizServer.fetchProjects(apiBase, "");
            if (authorization.get() != null || !projects.equals(anonymousProjects)) {
                throw new AssertionError("Anonymous project lookup must omit Authorization and return the same projects");
            }
            if (projects.size() != 2) {
                throw new AssertionError("Expected 2 projects, got " + projects.size());
            }

            JsonObject first = projects.get(0).getAsJsonObject();
            JsonObject second = projects.get(1).getAsJsonObject();
            assertString("first project id", "proj-1", first.get("id").getAsString());
            assertString("first project name", "Flashlight", first.get("name").getAsString());
            assertString("second project id", "proj-2", second.get("id").getAsString());
            assertString("second project fallback name", "(unnamed)", second.get("name").getAsString());
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
