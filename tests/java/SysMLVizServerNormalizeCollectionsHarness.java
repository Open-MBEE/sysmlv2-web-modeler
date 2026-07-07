import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SysMLVizServerNormalizeCollectionsHarness {

    public static void main(String[] args) {
        JsonArray direct = SysMLVizServer.normalizeCollectionList(
            JsonParser.parseString("[{\"id\":\"a\"}]"),
            "elements",
            "items"
        );
        assertSize("direct array", direct, 1);

        JsonArray elements = SysMLVizServer.normalizeCollectionList(
            JsonParser.parseString("{\"elements\":[{\"id\":\"e1\"}]}"),
            "elements",
            "items",
            "projects"
        );
        assertSize("elements wrapper", elements, 1);

        JsonArray items = SysMLVizServer.normalizeCollectionList(
            JsonParser.parseString("{\"items\":[{\"id\":\"i1\"}]}"),
            "elements",
            "items",
            "projects"
        );
        assertSize("items wrapper", items, 1);

        JsonArray projects = SysMLVizServer.normalizeCollectionList(
            JsonParser.parseString("{\"projects\":[{\"id\":\"p1\"}]}"),
            "elements",
            "items",
            "projects"
        );
        assertSize("projects wrapper", projects, 1);

        JsonArray missing = SysMLVizServer.normalizeCollectionList(
            JsonParser.parseString("{\"count\":0}"),
            "elements",
            "items"
        );
        assertSize("missing wrapper", missing, 0);

        JsonArray normalizedProjects = SysMLVizServer.normalizeProjectList(
            JsonParser.parseString("{\"items\":[{\"@id\":\"proj-1\",\"name\":\"Project One\"},{\"id\":\"proj-2\"}]}")
        );
        assertSize("normalized projects", normalizedProjects, 2);
        JsonObject first = normalizedProjects.get(0).getAsJsonObject();
        JsonObject second = normalizedProjects.get(1).getAsJsonObject();
        assertString("project 1 id", "proj-1", first.get("id"));
        assertString("project 1 name", "Project One", first.get("name"));
        assertString("project 2 id", "proj-2", second.get("id"));
        assertString("project 2 name fallback", "(unnamed)", second.get("name"));
    }

    private static void assertSize(String label, JsonArray array, int expected) {
        if (array == null || array.size() != expected) {
            throw new AssertionError(label + ": expected size " + expected + " but got " + (array == null ? "null" : array.size()));
        }
    }

    private static void assertString(String label, String expected, JsonElement actual) {
        String actualValue = actual == null || actual.isJsonNull() ? null : actual.getAsString();
        if (!expected.equals(actualValue)) {
            throw new AssertionError(label + ": expected \"" + expected + "\" but got \"" + actualValue + "\"");
        }
    }
}
