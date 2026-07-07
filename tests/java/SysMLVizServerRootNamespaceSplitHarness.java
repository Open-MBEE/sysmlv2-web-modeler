import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

public class SysMLVizServerRootNamespaceSplitHarness {

    public static void main(String[] args) {
        JsonArray elements = new JsonArray();

        JsonObject ns1 = new JsonObject();
        ns1.addProperty("@id", "ns-1");
        ns1.addProperty("@type", "Namespace");
        ns1.addProperty("qualifiedName", "alpha.sysml");
        elements.add(ns1);

        JsonObject ns2 = new JsonObject();
        ns2.addProperty("@id", "ns-2");
        ns2.addProperty("@type", "Namespace");
        ns2.addProperty("qualifiedName", "beta.sysml");
        elements.add(ns2);

        JsonObject rel1 = new JsonObject();
        rel1.addProperty("@id", "rel-1");
        rel1.addProperty("@type", "OwningMembership");
        JsonObject rel1Owner = new JsonObject();
        rel1Owner.addProperty("@id", "ns-1");
        rel1.add("owningRelationship", rel1Owner);
        elements.add(rel1);

        JsonObject rel2 = new JsonObject();
        rel2.addProperty("@id", "rel-2");
        rel2.addProperty("@type", "OwningMembership");
        JsonObject rel2Owner = new JsonObject();
        rel2Owner.addProperty("@id", "ns-2");
        rel2.add("owningRelationship", rel2Owner);
        elements.add(rel2);

        JsonObject pkg2 = new JsonObject();
        pkg2.addProperty("@id", "pkg-2");
        pkg2.addProperty("@type", "Package");
        pkg2.addProperty("declaredName", "Beta");
        JsonObject pkg2Owner = new JsonObject();
        pkg2Owner.addProperty("@id", "rel-2");
        pkg2.add("owningRelationship", pkg2Owner);
        elements.add(pkg2);

        JsonObject pkg1 = new JsonObject();
        pkg1.addProperty("@id", "pkg-1");
        pkg1.addProperty("@type", "Package");
        pkg1.addProperty("declaredName", "Alpha");
        JsonObject pkg1Owner = new JsonObject();
        pkg1Owner.addProperty("@id", "rel-1");
        pkg1.add("owningRelationship", pkg1Owner);
        elements.add(pkg1);

        List<SysMLVizServer.RootNamespaceChunk> documents = SysMLVizServer.splitRootNamespaceDocuments(elements);
        if (documents.size() != 2) {
            throw new AssertionError("Expected 2 root documents, got " + documents.size());
        }
        if (!"alpha.sysml".equals(documents.get(0).rootName)) {
            throw new AssertionError("Expected alpha.sysml as first root, got " + documents.get(0).rootName);
        }
        if (!"beta.sysml".equals(documents.get(1).rootName)) {
            throw new AssertionError("Expected beta.sysml as second root, got " + documents.get(1).rootName);
        }
        if (documents.get(0).elements.size() != 3) {
            throw new AssertionError("Expected 3 elements for first root, got " + documents.get(0).elements.size());
        }
        if (documents.get(1).elements.size() != 3) {
            throw new AssertionError("Expected 3 elements for second root, got " + documents.get(1).elements.size());
        }
        String firstFirstId = documents.get(0).elements.get(0).getAsJsonObject().get("@id").getAsString();
        String secondFirstId = documents.get(1).elements.get(0).getAsJsonObject().get("@id").getAsString();
        if (!"ns-1".equals(firstFirstId)) {
            throw new AssertionError("Expected root namespace first in alpha chunk, got " + firstFirstId);
        }
        if (!"ns-2".equals(secondFirstId)) {
            throw new AssertionError("Expected root namespace first in beta chunk, got " + secondFirstId);
        }
    }
}
