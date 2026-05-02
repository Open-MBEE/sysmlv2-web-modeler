import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;
import org.omg.sysml.lang.sysml.Element;

final class TextualModelService {

  String loadAndSerialize(TextualLoadRequest request) throws Exception {
    synchronized (SysMLVizServer.SYSML_LOCK) {
      SysMLInteractive textualSysml = SysMLInteractive.createInstance();
      textualSysml.setApiBasePath(request.apiBase());
      SysMLVizServer.log("[textual] load start: apiBase=" + request.apiBase()
        + ", projectId=" + request.projectId()
        + ", projectName=" + request.projectName()
        + ", branchId=" + request.branchId()
        + ", branchName=" + request.branchName()
        + ", elementName=" + SysMLVizServer.firstNonBlank(request.elementName(), "(top-level)"));
      String msg = textualSysml.load(request.toLoadParams());
      if (msg != null && msg.startsWith("ERROR:")) {
        SysMLVizServer.log("[textual] load error: " + msg);
        throw new IllegalStateException(msg);
      }
      SysMLVizServer.log("[textual] load complete: msg=" + (msg == null ? "(null)" : msg));
      int resourceCount = 0;
      for (Resource r : textualSysml.getInputResources()) {
        int contentSize = r == null ? -1 : r.getContents().size();
        SysMLVizServer.log("[textual] resource[" + resourceCount + "]: class="
          + (r == null ? "null" : r.getClass().getSimpleName())
          + ", uri=" + (r == null ? "null" : r.getURI())
          + ", contentSize=" + contentSize);
        resourceCount++;
      }
      SysMLVizServer.log("[textual] total resources loaded: " + resourceCount);
      SysMLVizServer.log("[textual] resolve start: " + SysMLVizServer.firstNonBlank(request.elementName(), "(top-level)"));
      Element resolvedElement = resolveLoadedElement(textualSysml, request.elementName());
      if (resolvedElement == null) {
        SysMLVizServer.log("[textual] resolve failed: " + SysMLVizServer.firstNonBlank(request.elementName(), "(top-level)"));
        throw new IllegalArgumentException(
          "Element not found or not resolvable: " + SysMLVizServer.firstNonBlank(request.elementName(), "(top-level)")
        );
      }
      SysMLVizServer.log(
        "[textual] resolve complete: eClass=" + resolvedElement.eClass().getName()
          + ", qualifiedName=" + SysMLVizServer.firstNonBlank(resolvedElement.getQualifiedName(), resolvedElement.getDeclaredName())
          + ", eResource=" + (resolvedElement.eResource() == null ? "null" : resolvedElement.eResource().getClass().getSimpleName())
          + ", eResourceSet=" + (resolvedElement.eResource() == null || resolvedElement.eResource().getResourceSet() == null
              ? "null" : resolvedElement.eResource().getResourceSet().getClass().getSimpleName())
      );
      SysMLVizServer.log("[textual] serialize start");
      return serializeElementText(resolvedElement);
    }
  }

  private SysMLInteractiveResult parseAndValidate(String modelText, String logPrefix) {
    SysMLInteractive textualSysml = SysMLInteractive.createInstance();
    SysMLVizServer.configureLibraries(textualSysml);
    SysMLInteractiveResult result = textualSysml.process(modelText, false);
    SysMLVizServer.logValidationDetails(logPrefix, textualSysml, result);
    return result;
  }

  JsonObject validateModel(String logPrefix, String modelText) throws Exception {
    synchronized (SysMLVizServer.SYSML_LOCK) {
      return SysMLVizServer.validationJson(parseAndValidate(modelText, logPrefix));
    }
  }

  JsonArray exportParsedJson(String modelText) throws Exception {
    synchronized (SysMLVizServer.SYSML_LOCK) {
      SysMLInteractiveResult result = parseAndValidate(modelText, "[textual/json]");
      if (result.getException() != null || result.hasErrors() || result.getRootElement() == null) {
        throw new IllegalArgumentException("Model has parse errors; fix validation issues before exporting JSON");
      }
      return SysMLVizServer.buildCommitChangePayload(result.getRootElement());
    }
  }

  JsonObject commitModel(TextualCommitRequest request) throws Exception {
    synchronized (SysMLVizServer.SYSML_LOCK) {
      SysMLVizServer.log("[textual/commit] parse start");
      SysMLInteractiveResult result = parseAndValidate(request.modelText(), "[textual/commit]");
      JsonObject validation = SysMLVizServer.validationJson(result);
      SysMLVizServer.log(
        "[textual/commit] parse complete: ok=" + validation.get("ok").getAsBoolean()
          + ", hasErrors=" + validation.get("hasErrors").getAsBoolean()
          + ", hasWarnings=" + validation.get("hasWarnings").getAsBoolean()
      );
      if (result.getException() != null || result.hasErrors() || result.getRootElement() == null) {
        SysMLVizServer.log("[textual/commit] validation failed");
        JsonObject error = new JsonObject();
        error.addProperty("ok", false);
        error.add("validation", validation);
        return error;
      }

      SysMLVizServer.log("[textual/commit] build change payload start");
      JsonArray changePayload = SysMLVizServer.buildCommitChangePayload(result.getRootElement());
      SysMLVizServer.log("[textual/commit] build change payload complete: changeCount=" + changePayload.size());
      SysMLVizServer.log("[textual/commit] commit bridge start");
      JsonObject commitResult = SysMLVizServer.runCommitBridge(
        request.apiBase(),
        request.projectName(),
        request.projectId(),
        request.branchName(),
        request.branchId(),
        request.commitMessage(),
        request.bearerToken(),
        changePayload
      );
      SysMLVizServer.log("[textual/commit] commit bridge complete");

      JsonObject success = new JsonObject();
      success.addProperty("ok", true);
      success.addProperty("changeCount", changePayload.size());
      success.add("validation", validation);
      success.add("commit", commitResult);
      return success;
    }
  }

  String serializeElementText(EObject element) {
    if (element == null) {
      return "";
    }
    SysMLVizServer.log("[textual/serialize] eClass=" + element.eClass().getName()
      + ", hasNodeModel=" + (NodeModelUtils.getNode(element) != null)
      + ", eResource=" + (element.eResource() == null ? "null" : element.eResource().getClass().getSimpleName())
      + ", eResourceSet=" + (element.eResource() == null || element.eResource().getResourceSet() == null
          ? "null" : element.eResource().getResourceSet().getClass().getSimpleName()));
    ICompositeNode node = NodeModelUtils.getNode(element);
    if (node != null) {
      String text = node.getText();
      SysMLVizServer.log("[textual/serialize] node model found: textLen=" + (text == null ? "null" : text.length()));
      if (text != null && !text.isBlank()) {
        SysMLVizServer.log("[textual/serialize] returning node model text");
        return SysMLVizServer.formatSysMLText(text);
      }
    } else {
      SysMLVizServer.log("[textual/serialize] no node model (element loaded from REST, not parsed from text)");
    }
    // The REST loader sets elementId/aliasIds on every element, which the Xtext grammar
    // rejects with "is not allowed to have a value". Clear those features in-place on the
    // original tree (this textualSysml instance is transient, so mutation is safe), then
    // also remove implied elements — same transforms sanitizedSerializationCopy does, but
    // keeping the element in its KerMLLazyLinkingResource so cross-references still resolve.
    SysMLVizServer.log("[textual/serialize] clearing serialization-only features in-place");
    java.util.List<EObject> snapshot = SysMLVizServer.collectEObjectSnapshot(element);
    SysMLVizServer.log("[textual/serialize] in-place snapshot size=" + snapshot.size());
    for (int i = snapshot.size() - 1; i >= 0; i--) {
      EObject obj = snapshot.get(i);
      if (SysMLVizServer.isImpliedEObject(obj)) {
        org.eclipse.emf.ecore.util.EcoreUtil.remove(obj);
      }
    }
    snapshot = SysMLVizServer.collectEObjectSnapshot(element);
    for (EObject obj : snapshot) {
      SysMLVizServer.clearSerializationOnlyFeatures(obj);
    }
    SysMLVizServer.log("[textual/serialize] diagnostics after in-place prep: "
      + SysMLVizServer.serializationDiagnostics(element));
    SysMLVizServer.log("[textual/serialize] in-place prep done; attempting serialize on original element in resource");
    try {
      String result = SysMLVizServer.getSysMLSerializer().serialize(element);
      if (result != null && !result.isBlank()) {
        SysMLVizServer.log("[textual/serialize] serialize succeeded: len=" + result.length());
        return SysMLVizServer.formatSysMLText(result);
      }
      SysMLVizServer.log("[textual/serialize] serialize returned blank");
    } catch (Exception | StackOverflowError e) {
      logSerializationFailure("[textual/serialize] original", element, e);
    }

    SysMLVizServer.log("[textual/serialize] retrying with sanitized detached copy");
    EObject detachedCopy = SysMLVizServer.sanitizedSerializationCopy(element);
    SysMLVizServer.log("[textual/serialize] detached copy diagnostics: "
      + SysMLVizServer.serializationDiagnostics(detachedCopy));
    try {
      String detachedResult = SysMLVizServer.getSysMLSerializer().serialize(detachedCopy);
      if (detachedResult != null && !detachedResult.isBlank()) {
        SysMLVizServer.log("[textual/serialize] detached-copy serialize succeeded: len=" + detachedResult.length());
        return SysMLVizServer.formatSysMLText(detachedResult);
      }
      SysMLVizServer.log("[textual/serialize] detached-copy serialize returned blank");
    } catch (Exception | StackOverflowError e) {
      logSerializationFailure("[textual/serialize] detached-copy", detachedCopy, e);
    }
    if (element instanceof Element sysmlElement) {
      SysMLVizServer.log("[textual/serialize] falling back to custom text renderer");
      String fallback = SysMLVizServer.renderSysmlFallback(sysmlElement);
      return "// Fallback SysML text generated from repository-loaded model because the Xtext serializer failed on the repository-loaded object\n"
        + fallback;
    }
    return "";
  }

  private void logSerializationFailure(String prefix, EObject element, Throwable error) {
    SysMLVizServer.log(prefix + " serialize failed ("
      + error.getClass().getSimpleName() + "): " + error.getMessage());
    SysMLVizServer.log(prefix + " diagnostics: " + SysMLVizServer.serializationDiagnostics(element));
    StackTraceElement[] stack = error.getStackTrace();
    int limit = Math.min(stack == null ? 0 : stack.length, 8);
    for (int i = 0; i < limit; i++) {
      SysMLVizServer.log(prefix + " stack[" + i + "] " + stack[i]);
    }
  }

  Element resolveLoadedElement(SysMLInteractive sysml, String elementName) {
    if (sysml == null) {
      return null;
    }
    if (elementName == null || elementName.isBlank()) {
      return resolveTopLevelLoadedElement(sysml);
    }

    List<Element> exactQualifiedMatches = new ArrayList<>();
    List<Element> exactDeclaredMatches = new ArrayList<>();
    List<Element> suffixQualifiedMatches = new ArrayList<>();

    for (org.eclipse.emf.ecore.resource.Resource resource : sysml.getInputResources()) {
      if (resource == null || resource.getContents().isEmpty()) {
        continue;
      }
      for (EObject object : SysMLVizServer.collectEObjectSnapshot(resource.getContents().get(0))) {
        if (!(object instanceof Element)) {
          continue;
        }
        Element element = (Element) object;
        String qualifiedName = SysMLVizServer.firstNonBlank(element.getQualifiedName());
        String declaredName = SysMLVizServer.firstNonBlank(element.getDeclaredName(), element.getName());

        if (elementName.equals(qualifiedName)) {
          exactQualifiedMatches.add(element);
        } else if (elementName.equals(declaredName)) {
          exactDeclaredMatches.add(element);
        } else if (!qualifiedName.isBlank() && qualifiedName.endsWith("::" + elementName)) {
          suffixQualifiedMatches.add(element);
        }
      }
    }

    List<Element> candidates = !exactQualifiedMatches.isEmpty()
      ? exactQualifiedMatches
      : (!exactDeclaredMatches.isEmpty() ? exactDeclaredMatches : suffixQualifiedMatches);
    if (candidates.isEmpty()) {
      return null;
    }

    Element best = candidates.get(0);
    int bestScore = elementSelectionScore(best);
    for (int i = 1; i < candidates.size(); i++) {
      Element candidate = candidates.get(i);
      int score = elementSelectionScore(candidate);
      if (score > bestScore || (score == bestScore && compareElementPreference(candidate, best) > 0)) {
        best = candidate;
        bestScore = score;
      }
    }

    if (candidates.size() > 1) {
      SysMLVizServer.log(
        "[resolve] multiple matches for '" + elementName + "', selected "
          + SysMLVizServer.firstNonBlank(best.getQualifiedName(), best.getDeclaredName(), best.getElementId())
          + " with score=" + bestScore + " from " + candidates.size() + " candidates"
      );
    }
    return best;
  }

  Element resolveTopLevelLoadedElement(SysMLInteractive sysml) {
    if (sysml == null) {
      return null;
    }
    for (org.eclipse.emf.ecore.resource.Resource resource : sysml.getInputResources()) {
      if (resource == null || resource.getContents().isEmpty()) {
        continue;
      }
      EObject root = resource.getContents().get(0);
      List<Element> namedChildren = new ArrayList<>();
      int bestDepth = Integer.MAX_VALUE;
      for (EObject candidateObj : SysMLVizServer.collectEObjectSnapshot(root)) {
        if (!(candidateObj instanceof Element element)) {
          continue;
        }
        if (candidateObj == root) {
          continue;
        }
        String qualifiedName = SysMLVizServer.firstNonBlank(element.getQualifiedName());
        String declaredName = SysMLVizServer.firstNonBlank(element.getDeclaredName(), element.getName());
        if (qualifiedName.isBlank() && declaredName.isBlank()) {
          continue;
        }
        int depth = SysMLVizServer.containmentDepthBelow(root, candidateObj);
        if (depth < 0) {
          continue;
        }
        if (depth < bestDepth) {
          namedChildren.clear();
          namedChildren.add(element);
          bestDepth = depth;
        } else if (depth == bestDepth) {
          namedChildren.add(element);
        }
      }
      if (!namedChildren.isEmpty()) {
        Element firstNamedTopLevel = namedChildren.get(0);
        String targetName = SysMLVizServer.firstNonBlank(
          firstNamedTopLevel.getQualifiedName(),
          firstNamedTopLevel.getDeclaredName(),
          firstNamedTopLevel.getName()
        );
        if (!targetName.isBlank()) {
          Element resolved = resolveLoadedElement(sysml, targetName);
          if (resolved != null) {
            SysMLVizServer.log("[resolve] no element provided, selected named top-level " + targetName);
            return resolved;
          }
        }
        SysMLVizServer.log(
          "[resolve] no element provided, selected named top-level "
            + SysMLVizServer.firstNonBlank(
              firstNamedTopLevel.getQualifiedName(),
              firstNamedTopLevel.getDeclaredName(),
              firstNamedTopLevel.getElementId()
            )
        );
        return firstNamedTopLevel;
      }
    }
    return null;
  }

  int elementSelectionScore(Element element) {
    if (element == null) {
      return -1;
    }
    int subtreeSize = SysMLVizServer.collectEObjectSnapshot(element).size();
    String qualifiedName = SysMLVizServer.firstNonBlank(element.getQualifiedName());
    int qualifiedDepth = qualifiedName.isBlank() ? 0 : qualifiedName.split("::", -1).length;
    String declaredName = SysMLVizServer.firstNonBlank(element.getDeclaredName(), element.getName());
    boolean hasName = !qualifiedName.isBlank() || !declaredName.isBlank();
    int nameBonus = hasName ? 1_000_000 : 0;
    int declaredNameScore = declaredName.isBlank() ? 0 : 1;
    return nameBonus + subtreeSize * 1000 + qualifiedDepth * 10 + declaredNameScore;
  }

  int compareElementPreference(Element left, Element right) {
    if (left == right) {
      return 0;
    }
    if (left == null) {
      return -1;
    }
    if (right == null) {
      return 1;
    }

    int leftOwned = left.getOwnedRelationship() == null ? 0 : left.getOwnedRelationship().size();
    int rightOwned = right.getOwnedRelationship() == null ? 0 : right.getOwnedRelationship().size();
    if (leftOwned != rightOwned) {
      return Integer.compare(leftOwned, rightOwned);
    }

    int leftDepth = SysMLVizServer.containmentDepth(left);
    int rightDepth = SysMLVizServer.containmentDepth(right);
    if (leftDepth != rightDepth) {
      return Integer.compare(rightDepth, leftDepth);
    }

    String leftQualified = SysMLVizServer.firstNonBlank(left.getQualifiedName(), left.getDeclaredName(), left.getName(), left.getElementId());
    String rightQualified = SysMLVizServer.firstNonBlank(right.getQualifiedName(), right.getDeclaredName(), right.getName(), right.getElementId());
    int lexical = rightQualified.compareTo(leftQualified);
    if (lexical != 0) {
      return lexical;
    }

    String leftId = SysMLVizServer.firstNonBlank(left.getElementId());
    String rightId = SysMLVizServer.firstNonBlank(right.getElementId());
    return rightId.compareTo(leftId);
  }

  record TextualLoadRequest(
    String apiBase,
    String projectName,
    String projectId,
    String branchName,
    String branchId,
    String elementName
  ) {
    Map<String, String> toLoadParams() {
      Map<String, String> loadParams = new HashMap<>();
      if (!projectId.isBlank()) loadParams.put("id", projectId);
      if (!projectName.isBlank()) loadParams.put("name", projectName);
      if (!branchId.isBlank()) loadParams.put("branch-id", branchId);
      if (!branchName.isBlank()) loadParams.put("branch", branchName);
      return loadParams;
    }
  }

  record TextualCommitRequest(
    String apiBase,
    String projectName,
    String projectId,
    String branchName,
    String branchId,
    String modelText,
    String commitMessage,
    String bearerToken
  ) {
  }
}
