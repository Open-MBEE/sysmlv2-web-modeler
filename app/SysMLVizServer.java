import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.util.Collections;
import java.util.List;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;
import org.omg.sysml.interactive.VizResult;
import org.omg.sysml.lang.sysml.Element;
import org.omg.sysml.lang.sysml.Feature;
import org.omg.sysml.lang.sysml.FeatureChaining;
import org.omg.sysml.delegate.invocation.OperationInvocationDelegateFactory;
import org.omg.sysml.delegate.setting.DerivedPropertySettingDelegateFactory;
import org.omg.sysml.util.traversal.Traversal;
import org.omg.sysml.util.traversal.facade.impl.JsonElementProcessingFacade;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.impl.ResourceDescriptionsData;
import org.eclipse.xtext.serializer.ISerializer;
import org.eclipse.xtext.validation.Issue;
import org.omg.sysml.xtext.SysMLStandaloneSetup;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import org.eclipse.emf.common.notify.NotificationChain;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Injector;

public class SysMLVizServer {

  static final Object SYSML_LOCK = new Object();
  private static volatile String LAST_LOAD_KEY = null;
  private static final Map<String, String> ENV_DEFAULTS = buildEnvDefaults();
  private static final Object LOG_LOCK = new Object();
  private static final LinkedList<LogEntry> LOG_BUFFER = new LinkedList<>();
  private static final AtomicLong LOG_SEQUENCE = new AtomicLong(0);
  private static final int MAX_LOG_ENTRIES = 500;
  private static final long DEFAULT_RENDER_TIMEOUT_MS = 180_000L;
  private static final long DEFAULT_TEXTUAL_TIMEOUT_MS = 180_000L;
  private static final long DEFAULT_COMMIT_TIMEOUT_MS = 300_000L;
  private static final long RENDER_TIMEOUT_MS = parseLongOrDefault(
    System.getenv("SYSML_RENDER_TIMEOUT_MS"),
    DEFAULT_RENDER_TIMEOUT_MS
  );
  private static final long TEXTUAL_TIMEOUT_MS = parseLongOrDefault(
    System.getenv("SYSML_TEXTUAL_TIMEOUT_MS"),
    DEFAULT_TEXTUAL_TIMEOUT_MS
  );
  private static final long COMMIT_TIMEOUT_MS = parseLongOrDefault(
    System.getenv("SYSML_COMMIT_TIMEOUT_MS"),
    DEFAULT_COMMIT_TIMEOUT_MS
  );
  private static final String DEFAULT_COMMIT_MESSAGE = "Replace model from textual editor";
  private static final String PYTHON_BIN = firstNonBlank(System.getenv("PYTHON_BIN"), "python");
  private static final String SYSML_LIBRARY_PATH = detectSysMLLibraryPath();
  private static final String PILOT_REF = detectPilotRef();
  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
  private static final TextualModelService TEXTUAL_MODEL_SERVICE = new TextualModelService();
  private static volatile ISerializer SYSML_SERIALIZER = null;
  private static volatile boolean SYSML_DELEGATES_REGISTERED = false;

  // Reflection-based patch for elementId serialization.
  // Element_elementId_SettingDelegate.isSet() unconditionally returns true, so
  // eUnset() has no effect and the Xtext serializer always sees elementId as set,
  // failing with "not allowed to have a value". We replace the per-instance
  // ELEMENT_ID__ESETTING_DELEGATE field with a no-op delegate before serializing.
  //
  // IMPORTANT: this field is lazily initialized (not in the static init block)
  // because Class.forName("ElementImpl") triggers ElementImpl's static initializers,
  // which eagerly cache EOperation invocation delegates (EFFECTIVE_NAME__EINVOCATION_DELEGATE etc.)
  // If the OperationInvocationDelegateFactory is not in the registry yet, those delegates
  // get cached as BasicInvocationDelegate (which throws UnsupportedOperationException).
  // By deferring to first use inside clearSerializationOnlyFeatures(), we guarantee
  // SysMLInteractive.createInstance() has already registered the factory via
  // KerMLStandaloneSetup.doSetup().
  private static volatile java.lang.reflect.Field ELEMENT_ID_DELEGATE_FIELD = null;
  private static final EStructuralFeature.Internal.SettingDelegate ELEMENT_ID_UNSET_DELEGATE =
    new EStructuralFeature.Internal.SettingDelegate() {
      public EStructuralFeature.Setting dynamicSetting(
          org.eclipse.emf.ecore.InternalEObject o,
          EStructuralFeature.Internal.DynamicValueHolder dv, int di) { return null; }
      public Object dynamicGet(
          org.eclipse.emf.ecore.InternalEObject o,
          EStructuralFeature.Internal.DynamicValueHolder dv, int di, boolean r, boolean c) { return null; }
      public void dynamicSet(
          org.eclipse.emf.ecore.InternalEObject o,
          EStructuralFeature.Internal.DynamicValueHolder dv, int di, Object v) {}
      public boolean dynamicIsSet(
          org.eclipse.emf.ecore.InternalEObject o,
          EStructuralFeature.Internal.DynamicValueHolder dv, int di) { return false; }
      public void dynamicUnset(
          org.eclipse.emf.ecore.InternalEObject o,
          EStructuralFeature.Internal.DynamicValueHolder dv, int di) {}
      public NotificationChain dynamicInverseAdd(
          org.eclipse.emf.ecore.InternalEObject o,
          EStructuralFeature.Internal.DynamicValueHolder dv, int di,
          org.eclipse.emf.ecore.InternalEObject other, NotificationChain n) { return n; }
      public NotificationChain dynamicInverseRemove(
          org.eclipse.emf.ecore.InternalEObject o,
          EStructuralFeature.Internal.DynamicValueHolder dv, int di,
          org.eclipse.emf.ecore.InternalEObject other, NotificationChain n) { return n; }
    };

  private static java.lang.reflect.Field resolveElementIdDelegateField() {
    try {
      Class<?> cls = Class.forName("org.omg.sysml.lang.sysml.impl.ElementImpl");
      java.lang.reflect.Field f = cls.getDeclaredField("ELEMENT_ID__ESETTING_DELEGATE");
      f.setAccessible(true);
      return f;
    } catch (Exception e) {
      return null;
    }
  }
  private static final ExecutorService RENDER_EXECUTOR = Executors.newCachedThreadPool();
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(20))
    .version(HttpClient.Version.HTTP_1_1)
    .build();
  private static final String UI_MODE = detectUiMode();
  private static final boolean ALLOW_UI_API_OVERRIDE = parseBooleanOrDefault(
    System.getenv("SYSML_ALLOW_UI_API_OVERRIDE"),
    !"embedded".equals(UI_MODE)
  );

  static final class LogEntry {
    final long id;
    final String timestamp;
    final String message;

    LogEntry(long id, String timestamp, String message) {
      this.id = id;
      this.timestamp = timestamp;
      this.message = message;
    }
  }

  static String readBody(HttpExchange ex) throws IOException {
    try (InputStream is = ex.getRequestBody()) {
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  // tiny JSON extractor (no dependency). For safety/robustness, see note below.
  static String jsonStringField(String json, String field) {
    // Very small/naive: expects "field":"...". Good enough for controlled input.
    // If you want robust parsing, use Jackson/Gson.
    String needle = "\"" + field + "\"";
    int i = json.indexOf(needle);
    if (i < 0) return null;
    i = json.indexOf(':', i);
    if (i < 0) return null;
    i++;
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
    if (i >= json.length() || json.charAt(i) != '"') return null;
    i++;
    StringBuilder sb = new StringBuilder();
    boolean esc = false;
    for (; i < json.length(); i++) {
      char c = json.charAt(i);
      if (esc) {
        // handle common escapes minimally
        if (c == 'n') sb.append('\n');
        else if (c == 't') sb.append('\t');
        else sb.append(c);
        esc = false;
      } else if (c == '\\') {
        esc = true;
      } else if (c == '"') {
        return sb.toString();
      } else {
        sb.append(c);
      }
    }
    return null;
  }

  static Map<String, String> buildEnvDefaults() {
    Map<String, String> m = new HashMap<>();
    putIfPresent(m, "apiBase", "SYSML_API_BASE");
    putIfPresent(m, "apiToken", "SYSML_API_TOKEN");
    return Collections.unmodifiableMap(m);
  }

  static String detectUiMode() {
    String configured = firstNonBlank(System.getenv("SYSML_UI_MODE")).toLowerCase();
    return "embedded".equals(configured) ? "embedded" : "standalone";
  }

  static boolean parseBooleanOrDefault(String value, boolean defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    String normalized = value.trim().toLowerCase();
    if (normalized.isBlank()) {
      return defaultValue;
    }
    if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
      return true;
    }
    if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
      return false;
    }
    return defaultValue;
  }

  static void putIfPresent(Map<String, String> target, String key, String envName) {
    String value = System.getenv(envName);
    if (value != null) {
      value = value.trim();
      if (!value.isBlank()) {
        target.put(key, value);
      }
    }
  }

  static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null) {
        value = value.trim();
        if (!value.isBlank()) {
          return value;
        }
      }
    }
    return "";
  }

  static String safeQualifiedName(Element element) {
    if (element == null) {
      return "";
    }
    try {
      return firstNonBlank(element.getQualifiedName());
    } catch (RuntimeException ex) {
      logSafeDerivedNameFailure("qualifiedName", element, ex);
      return "";
    }
  }

  static String safeDeclaredName(Element element) {
    if (element == null) {
      return "";
    }
    try {
      return firstNonBlank(element.getDeclaredName());
    } catch (RuntimeException ex) {
      logSafeDerivedNameFailure("declaredName", element, ex);
      return "";
    }
  }

  static String safeName(Element element) {
    if (element == null) {
      return "";
    }
    try {
      return firstNonBlank(element.getName());
    } catch (RuntimeException ex) {
      logSafeDerivedNameFailure("name", element, ex);
      return "";
    }
  }

  static String safeDisplayName(Element element) {
    return firstNonBlank(
      safeQualifiedName(element),
      safeDeclaredName(element),
      safeName(element),
      element == null ? "" : firstNonBlank(element.getElementId())
    );
  }

  static void logSafeDerivedNameFailure(String property, Element element, RuntimeException ex) {
    Throwable root = rootCause(ex);
    String kind = element == null || element.eClass() == null ? "Element" : element.eClass().getName();
    String elementId = "";
    try {
      elementId = element == null ? "" : firstNonBlank(element.getElementId());
    } catch (RuntimeException ignored) {
      elementId = "";
    }
    log("[resolve] ignoring " + root.getClass().getSimpleName()
      + " while reading " + property
      + " for " + kind
      + " id=" + firstNonBlank(elementId, "(blank)")
      + ": " + firstNonBlank(root.getMessage(), ex.getMessage()));
  }

  static String detectSysMLLibraryPath() {
    String configured = firstNonBlank(System.getenv("SYSML_LIBRARY_PATH"));
    if (!configured.isBlank() && Files.isDirectory(Paths.get(configured))) {
      return configured;
    }

    String[] candidates = new String[] {
      "sysml.library",
      "..\\..\\SysML-v2-Pilot-Implementation\\sysml.library",
      "..\\sysml.library",
      "/opt/sysml.library"
    };
    for (String candidate : candidates) {
      try {
        Path path = Paths.get(candidate).toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
          return path.toString();
        }
      } catch (Exception ignored) {
      }
    }
    return configured;
  }

  static String detectPilotRef() {
    String configured = firstNonBlank(System.getenv("PILOT_REF"));
    if (!configured.isBlank()) {
      return configured;
    }
    String[] candidates = new String[] {
      ".pilot-version",
      "..\\.pilot-version",
      "/opt/app/.pilot-version"
    };
    for (String candidate : candidates) {
      try {
        Path path = Paths.get(candidate).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
          continue;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
          String trimmed = line == null ? "" : line.trim();
          if (!trimmed.isBlank() && !trimmed.startsWith("#")) {
            return trimmed;
          }
        }
      } catch (Exception ignored) {
      }
    }
    return configured;
  }

  static void configureLibraries(SysMLInteractive sysml) {
    if (sysml == null) {
      return;
    }
    String libraryPath = firstNonBlank(SYSML_LIBRARY_PATH);
    if (libraryPath.isBlank()) {
      log("[libraries] skipped: no library path configured");
      return;
    }
    try {
      Path root = Paths.get(libraryPath);
      log("[libraries] loading from " + root.toAbsolutePath().normalize()
        + ", exists=" + Files.exists(root)
        + ", isDirectory=" + Files.isDirectory(root));
      sysml.loadLibrary(libraryPath);
    } catch (Exception e) {
      logException("[libraries] failed to load SysML library from " + libraryPath, e);
    }
  }

  static void ensureSysMLDelegatesRegistered() {
    if (SYSML_DELEGATES_REGISTERED) {
      return;
    }
    synchronized (SYSML_LOCK) {
      if (SYSML_DELEGATES_REGISTERED) {
        return;
      }
      EStructuralFeature.Internal.SettingDelegate.Factory.Registry.INSTANCE.put(
        DerivedPropertySettingDelegateFactory.SYSML_ANNOTATION,
        new DerivedPropertySettingDelegateFactory()
      );
      EOperation.Internal.InvocationDelegate.Factory.Registry.INSTANCE.put(
        OperationInvocationDelegateFactory.SYSML_ANNOTATION,
        new OperationInvocationDelegateFactory()
      );
      SYSML_DELEGATES_REGISTERED = true;
      log("[startup] registered SysML delegate factories");
    }
  }

  static String jsonEscape(String s) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': out.append("\\\""); break;
        case '\\': out.append("\\\\"); break;
        case '\b': out.append("\\b"); break;
        case '\f': out.append("\\f"); break;
        case '\n': out.append("\\n"); break;
        case '\r': out.append("\\r"); break;
        case '\t': out.append("\\t"); break;
        default:
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    return out.toString();
  }

  static String defaultsJson() {
    JsonObject json = new JsonObject();
    String apiBase = firstNonBlank(ENV_DEFAULTS.get("apiBase"));
    if (!apiBase.isBlank()) {
      json.addProperty("apiBase", apiBase);
    }
    String apiToken = firstNonBlank(ENV_DEFAULTS.get("apiToken"));
    if (!apiToken.isBlank()) {
      json.addProperty("apiToken", apiToken);
    }
    json.addProperty("uiMode", UI_MODE);
    json.addProperty("showApiBaseField", !"embedded".equals(UI_MODE));
    json.addProperty("showBearerTokenField", !"embedded".equals(UI_MODE));
    json.addProperty("allowUiApiOverride", ALLOW_UI_API_OVERRIDE);
    if (!PILOT_REF.isBlank()) {
      json.addProperty("pilotRef", PILOT_REF);
    }
    return GSON.toJson(json);
  }

  static String describeConfigSummary() {
    return "apiBase=" + firstNonBlank(ENV_DEFAULTS.get("apiBase"), "(unset)")
      + ", pilotRef=" + firstNonBlank(PILOT_REF, "(unset)")
      + ", uiMode=" + UI_MODE
      + ", showApiBaseField=" + (!"embedded".equals(UI_MODE))
      + ", showBearerTokenField=" + (!"embedded".equals(UI_MODE))
      + ", allowUiApiOverride=" + ALLOW_UI_API_OVERRIDE;
  }

  record TokenResolution(String token, String source) {}

  static TokenResolution resolveToken(HttpExchange ex, JsonObject request) {
    if ("embedded".equals(UI_MODE)) {
      String t = firstNonBlank(System.getenv("SYSML_API_TOKEN"));
      return new TokenResolution(t, t.isBlank() ? "missing" : "env(embedded)");
    }
    String bodyToken = request == null ? null : jsonString(request, "bearerToken");
    if (bodyToken != null && !bodyToken.trim().isBlank()) {
      return new TokenResolution(bodyToken.trim(), "body");
    }
    String headerToken = ex == null ? null : ex.getRequestHeaders().getFirst("Authorization");
    if (headerToken != null && !headerToken.trim().isBlank()) {
      return new TokenResolution(headerToken.trim(), "header");
    }
    String envToken = firstNonBlank(System.getenv("SYSML_API_TOKEN"));
    return new TokenResolution(envToken, envToken.isBlank() ? "missing" : "env");
  }

  static String bearerTokenSource(HttpExchange ex, JsonObject request) {
    return resolveToken(ex, request).source();
  }

  static String loadJsonModelOrError(SysMLInteractive sysml, String modelName, String modelJson) {
    try {
      Method method = sysml.getClass().getMethod("loadJsonModel", String.class, String.class);
      Object result = method.invoke(sysml, modelName, modelJson);
      return result != null ? result.toString() : null;
    } catch (NoSuchMethodException e) {
      return "ERROR: loadJsonModel is not available in the current Pilot runtime";
    } catch (Exception e) {
      return "ERROR: " + e.getMessage();
    }
  }

  static JsonObject parseJsonObject(String body) {
    JsonElement parsed = JsonParser.parseString(body);
    if (!parsed.isJsonObject()) {
      throw new IllegalArgumentException("Request body must be a JSON object");
    }
    return parsed.getAsJsonObject();
  }

  static String jsonString(JsonObject json, String field) {
    JsonElement value = json.get(field);
    if (value == null || value.isJsonNull()) {
      return null;
    }
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new IllegalArgumentException("Field '" + field + "' must be a string");
    }
    return value.getAsString();
  }

  static String bearerTokenFromRequest(HttpExchange ex, JsonObject request) {
    return resolveToken(ex, request).token();
  }

  static String apiBaseSource(String requestApiBase) {
    String envApiBase = firstNonBlank(ENV_DEFAULTS.get("apiBase"));
    String providedApiBase = firstNonBlank(requestApiBase);
    if ("embedded".equals(UI_MODE)) {
      return envApiBase.isBlank() ? "request(fallback)" : "env(embedded)";
    }
    if (!ALLOW_UI_API_OVERRIDE) {
      return envApiBase.isBlank() ? "request(fallback)" : "env(override-disabled)";
    }
    if (!providedApiBase.isBlank()) {
      return "request";
    }
    return envApiBase.isBlank() ? "missing" : "env";
  }

  static String apiBaseFromRequest(String requestApiBase) {
    String envApiBase = firstNonBlank(ENV_DEFAULTS.get("apiBase"));
    if ("embedded".equals(UI_MODE) || !ALLOW_UI_API_OVERRIDE) {
      return firstNonBlank(envApiBase, requestApiBase);
    }
    return firstNonBlank(requestApiBase, envApiBase);
  }

  static Map<String, String> queryParams(String rawQuery) {
    Map<String, String> m = new HashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) return m;
    for (String pair : rawQuery.split("&")) {
      int i = pair.indexOf('=');
      String k = i >= 0 ? pair.substring(0, i) : pair;
      String v = i >= 0 ? pair.substring(i + 1) : "";
      m.put(
        URLDecoder.decode(k, StandardCharsets.UTF_8),
        URLDecoder.decode(v, StandardCharsets.UTF_8)
      );
    }
    return m;
  }

  static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
    ex.getResponseHeaders().set("Content-Type", contentType);
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(code, body.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(body);
    }
  }

  static void sendText(HttpExchange ex, int code, String contentType, String body) throws IOException {
    send(ex, code, contentType, body.getBytes(StandardCharsets.UTF_8));
  }

  static String stackTrace(Throwable t) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    t.printStackTrace(pw);
    pw.flush();
    return sw.toString();
  }

  static String staticContentType(String filename) {
    if (filename.endsWith(".svg")) return "image/svg+xml";
    if (filename.endsWith(".png")) return "image/png";
    if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
    if (filename.endsWith(".ico")) return "image/x-icon";
    if (filename.endsWith(".css")) return "text/css; charset=utf-8";
    if (filename.endsWith(".js")) return "application/javascript; charset=utf-8";
    return "application/octet-stream";
  }

  static void log(String message) {
    String line = message == null ? "" : message;
    String timestamp = Instant.now().toString();
    synchronized (LOG_LOCK) {
      LOG_BUFFER.add(new LogEntry(
        LOG_SEQUENCE.incrementAndGet(),
        timestamp,
        line
      ));
      while (LOG_BUFFER.size() > MAX_LOG_ENTRIES) {
        LOG_BUFFER.removeFirst();
      }
    }
    System.out.println(timestamp + " " + line);
    System.out.flush();
  }

  static void logException(String prefix, Throwable t) {
    log(prefix);
    for (String line : stackTrace(t).split("\\R")) {
      if (!line.isBlank()) {
        log(line);
      }
    }
  }

  static long parseLongOrDefault(String value, long defaultValue) {
    if (value == null || value.isBlank()) return defaultValue;
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  static String logEntryJson(LogEntry entry) {
    return new StringBuilder()
      .append('{')
      .append("\"id\":").append(entry.id).append(',')
      .append("\"timestamp\":\"").append(jsonEscape(entry.timestamp)).append("\",")
      .append("\"message\":\"").append(jsonEscape(entry.message)).append("\"")
      .append('}')
      .toString();
  }

  static String logsJson(long afterId) {
    List<LogEntry> entries = new ArrayList<>();
    long lastId = afterId;
    synchronized (LOG_LOCK) {
      for (LogEntry entry : LOG_BUFFER) {
        if (entry.id > afterId) {
          entries.add(entry);
          lastId = entry.id;
        }
      }
    }

    StringBuilder json = new StringBuilder();
    json.append('{');
    json.append("\"lastId\":").append(lastId).append(',');
    json.append("\"entries\":[");
    for (int i = 0; i < entries.size(); i++) {
      if (i > 0) json.append(',');
      json.append(logEntryJson(entries.get(i)));
    }
    json.append("]}");
    return json.toString();
  }

  static ISerializer getSysMLSerializer() {
    if (SYSML_SERIALIZER != null) {
      return SYSML_SERIALIZER;
    }
    synchronized (SysMLVizServer.class) {
      if (SYSML_SERIALIZER == null) {
        ensureSysMLDelegatesRegistered();
        Injector injector = new SysMLStandaloneSetup().createInjectorAndDoEMFRegistration();
        SYSML_SERIALIZER = injector.getInstance(ISerializer.class);
      }
    }
    return SYSML_SERIALIZER;
  }

  static String serializeElementText(EObject element) {
    return TEXTUAL_MODEL_SERVICE.serializeElementText(element);
  }

  static String renderSysmlFallback(Element element) {
    StringBuilder out = new StringBuilder();
    appendSysmlFallback(out, element, 0);
    return out.toString().trim();
  }

  static void appendSysmlFallback(StringBuilder out, Element element, int indent) {
    if (element == null) {
      return;
    }
    String kind = element.eClass().getName();
    String name = fallbackElementName(element);
    List<Element> children = fallbackOwnedElements(element);

    switch (kind) {
      case "Package":
        appendIndented(out, indent, "package " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "PartUsage":
        appendIndented(out, indent, fallbackFeatureDirection(element) + "part " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "PartDefinition":
        appendIndented(out, indent, "part def " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "ItemUsage":
        appendIndented(out, indent, fallbackFeatureDirection(element) + "item " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "ItemDefinition":
        appendIndented(out, indent, "item def " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "PortUsage":
        appendIndented(out, indent, fallbackFeatureDirection(element) + "port " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "PortDefinition":
        appendIndented(out, indent, "port def " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "AttributeUsage":
        appendIndented(out, indent, fallbackFeatureDirection(element) + "attribute " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "AttributeDefinition":
        appendIndented(out, indent, "attribute def " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "RequirementUsage":
        appendIndented(out, indent, "requirement " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "RequirementDefinition":
        appendIndented(out, indent, "requirement def " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "ActionUsage":
        appendIndented(out, indent, "action " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "ActionDefinition":
        appendIndented(out, indent, "action def " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "CalculationUsage":
        appendIndented(out, indent, "calc " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "CalculationDefinition":
        appendIndented(out, indent, "calc def " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "VerificationCaseUsage":
        appendIndented(out, indent, "verification " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "VerificationCaseDefinition":
        appendIndented(out, indent, "verification def " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "ViewUsage":
        appendIndented(out, indent, "view " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "ViewDefinition":
        appendIndented(out, indent, "view def " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "ConstraintUsage":
        appendIndented(out, indent, "constraint " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "ConstraintDefinition":
        appendIndented(out, indent, "constraint def " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "StateUsage":
        appendIndented(out, indent, "state " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "StateDefinition":
        appendIndented(out, indent, "state def " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "ExhibitStateUsage":
        appendIndented(out, indent, "exhibit state " + name);
        appendBlockOrSemicolon(out, indent, children);
        return;
      case "ForkNode":
        if (!name.isBlank() && !"_".equals(name)) {
          appendIndented(out, indent, "fork " + name + ";");
          out.append('\n');
        }
        return;
      case "JoinNode":
        if (!name.isBlank() && !"_".equals(name)) {
          appendIndented(out, indent, "join " + name + ";");
          out.append('\n');
        }
        return;
      case "MergeNode":
        if (!name.isBlank() && !"_".equals(name)) {
          appendIndented(out, indent, "merge " + name + ";");
          out.append('\n');
        }
        return;
      case "DecideNode":
        if (!name.isBlank() && !"_".equals(name)) {
          appendIndented(out, indent, "decide " + name + ";");
          out.append('\n');
        }
        return;
      case "Documentation":
      case "Comment": {
        String body = fallbackDocBody(element);
        if (!body.isBlank()) {
          appendIndented(out, indent, "doc /* " + body + " */");
          out.append('\n');
        }
        return;
      }
      case "AllocationUsage":
      case "AllocationDefinition":
      case "SuccessionAsUsage":
      case "BindingConnectorAsUsage":
      case "FlowUsage":
      case "FlowEnd":
      case "ReferenceUsage":
      case "Multiplicity":
      case "MultiplicityRange":
      case "FeatureReferenceExpression":
      case "FeatureChainExpression":
      case "OperatorExpression":
      case "InvocationExpression":
      case "Feature":
      case "RenderingUsage":
      case "TransitionUsage":
      case "PerformActionUsage":
      case "ConnectionUsage":
      case "ConnectionDefinition":
      case "LiteralInteger":
      case "LiteralReal":
      case "LiteralBoolean":
      case "LiteralString":
        return;
      default:
        if (!children.isEmpty()) {
          appendIndented(out, indent, "// Fallback for " + kind + " " + name);
          out.append('\n');
          for (Element child : children) {
            appendSysmlFallback(out, child, indent);
          }
        } else if (!name.isBlank()) {
          appendIndented(out, indent, "// Fallback for " + kind + " " + name + ";");
          out.append('\n');
        }
    }
  }

  static void appendBlockOrSemicolon(StringBuilder out, int indent, List<Element> children) {
    if (children == null || children.isEmpty()) {
      out.append(";\n");
      return;
    }
    StringBuilder body = new StringBuilder();
    for (Element child : children) {
      appendSysmlFallback(body, child, indent + 1);
    }
    String bodyText = body.toString();
    if (bodyText.isBlank()) {
      out.append(";\n");
    } else {
      out.append(" {\n");
      out.append(bodyText);
      appendIndented(out, indent, "}");
      out.append('\n');
    }
  }

  static void appendIndented(StringBuilder out, int indent, String line) {
    for (int i = 0; i < indent; i++) {
      out.append("  ");
    }
    out.append(line);
  }

  static String fallbackElementName(Element element) {
    String declaredName = firstNonBlank(safeDeclaredName(element), safeName(element));
    if (!declaredName.isBlank()) {
      return declaredName;
    }
    String qualifiedName = safeQualifiedName(element);
    if (!qualifiedName.isBlank()) {
      int idx = qualifiedName.lastIndexOf("::");
      return idx >= 0 ? qualifiedName.substring(idx + 2) : qualifiedName;
    }
    return "_";
  }

  static List<Element> fallbackOwnedElements(Element element) {
    List<Element> children = new ArrayList<>();
    if (element == null || element.getOwnedRelationship() == null) {
      return children;
    }
    element.getOwnedRelationship().forEach(relationship -> {
      if (relationship == null || relationship.getOwnedRelatedElement() == null) {
        return;
      }
      relationship.getOwnedRelatedElement().forEach(related -> {
        if (related instanceof Element relatedElement && relatedElement != element && !isImpliedEObject(relatedElement)) {
          children.add(relatedElement);
        }
      });
    });
    return children;
  }

  static String fallbackFeatureDirection(Element element) {
    try {
      EStructuralFeature f = element.eClass().getEStructuralFeature("direction");
      if (f == null) return "";
      Object val = element.eGet(f);
      if (val == null) return "";
      String lit = val.toString().toLowerCase();
      if ("in".equals(lit) || "out".equals(lit) || "inout".equals(lit)) return lit + " ";
    } catch (Exception ignored) {}
    return "";
  }

  static String fallbackDocBody(Element element) {
    try {
      EStructuralFeature f = element.eClass().getEStructuralFeature("body");
      if (f == null) return "";
      Object val = element.eGet(f);
      return (val instanceof String s) ? s.trim() : "";
    } catch (Exception ignored) {}
    return "";
  }

  static String formatSysMLText(String text) {
    if (text == null) {
      return "";
    }
    String source = text.replace("\r\n", "\n").replace('\r', '\n').trim();
    if (source.isEmpty()) {
      return "";
    }

    StringBuilder out = new StringBuilder();
    int indent = 0;
    boolean startOfLine = true;
    boolean pendingSpace = false;
    boolean inString = false;
    boolean inLineComment = false;

    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

      if (inLineComment) {
        if (startOfLine) {
          appendIndent(out, indent);
          startOfLine = false;
        }
        out.append(c);
        if (c == '\n') {
          inLineComment = false;
          startOfLine = true;
          pendingSpace = false;
        }
        continue;
      }

      if (inString) {
        if (startOfLine) {
          appendIndent(out, indent);
          startOfLine = false;
        }
        out.append(c);
        if (c == '"' && !isEscaped(source, i)) {
          inString = false;
        }
        continue;
      }

      if (c == '/' && next == '/') {
        if (!startOfLine && pendingSpace) {
          out.append(' ');
        }
        if (startOfLine) {
          appendIndent(out, indent);
          startOfLine = false;
        }
        out.append('/').append('/');
        i++;
        pendingSpace = false;
        inLineComment = true;
        continue;
      }

      if (Character.isWhitespace(c)) {
        pendingSpace = !startOfLine;
        continue;
      }

      if (c == '"') {
        if (!startOfLine && pendingSpace) {
          out.append(' ');
        }
        if (startOfLine) {
          appendIndent(out, indent);
          startOfLine = false;
        }
        out.append(c);
        pendingSpace = false;
        inString = true;
        continue;
      }

      if (c == '}') {
        trimTrailingWhitespace(out);
        if (!startOfLine) {
          out.append('\n');
        }
        indent = Math.max(0, indent - 1);
        appendIndent(out, indent);
        out.append('}');
        startOfLine = false;
        pendingSpace = false;
        if (next != ';' && next != '}' && next != '\0') {
          out.append('\n');
          startOfLine = true;
        }
        continue;
      }

      if (startOfLine) {
        appendIndent(out, indent);
        startOfLine = false;
      } else if (pendingSpace) {
        out.append(' ');
      }

      out.append(c);
      pendingSpace = false;

      if (c == '{') {
        out.append('\n');
        indent++;
        startOfLine = true;
      } else if (c == ';') {
        out.append('\n');
        startOfLine = true;
      }
    }

    String formatted = out.toString().replaceAll("[ \t]+\n", "\n").trim();
    return formatted.isEmpty() ? source : formatted;
  }

  static void appendIndent(StringBuilder out, int indent) {
    for (int i = 0; i < indent; i++) {
      out.append("  ");
    }
  }

  static void trimTrailingWhitespace(StringBuilder out) {
    while (out.length() > 0) {
      char last = out.charAt(out.length() - 1);
      if (last == ' ' || last == '\t') {
        out.setLength(out.length() - 1);
      } else {
        break;
      }
    }
  }

  static boolean isEscaped(String text, int index) {
    int slashCount = 0;
    for (int i = index - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
      slashCount++;
    }
    return slashCount % 2 == 1;
  }

  static EObject sanitizedSerializationCopy(EObject element) {
    EObject copy = EcoreUtil.copy(element);
    List<EObject> snapshot = collectEObjectSnapshot(copy);
    for (int i = snapshot.size() - 1; i >= 0; i--) {
      EObject current = snapshot.get(i);
      if (isImpliedEObject(current)) {
        EcoreUtil.remove(current);
      }
    }
    snapshot = collectEObjectSnapshot(copy);
    for (EObject current : snapshot) {
      clearSerializationOnlyFeatures(current);
    }
    return copy;
  }

  static String serializationDiagnostics(EObject root) {
    if (root == null) {
      return "root=(null)";
    }
    List<EObject> snapshot = collectEObjectSnapshot(root);
    java.util.Map<String, Integer> classCounts = new java.util.TreeMap<>();
    int proxies = 0;
    int missingNames = 0;
    int missingElementIds = 0;
    int missingContainers = 0;
    int maxDepth = 0;
    for (EObject current : snapshot) {
      String eClassName = current.eClass().getName();
      classCounts.merge(eClassName, 1, Integer::sum);
      if (current.eIsProxy()) {
        proxies++;
      }
      int depth = containmentDepthBelow(root, current);
      if (depth > maxDepth) {
        maxDepth = depth;
      }
      if (current != root && current.eContainer() == null) {
        missingContainers++;
      }
      if (current instanceof Element element) {
        String declaredName = firstNonBlank(safeDeclaredName(element), safeName(element));
        if (declaredName.isBlank()) {
          missingNames++;
        }
        if (firstNonBlank(element.getElementId()).isBlank()) {
          missingElementIds++;
        }
      }
    }
    StringBuilder topKinds = new StringBuilder();
    int emitted = 0;
    for (java.util.Map.Entry<String, Integer> entry : classCounts.entrySet()) {
      if (emitted >= 8) {
        topKinds.append(", ...");
        break;
      }
      if (emitted > 0) {
        topKinds.append(", ");
      }
      topKinds.append(entry.getKey()).append('=').append(entry.getValue());
      emitted++;
    }
    return "rootClass=" + root.eClass().getName()
      + ", subtreeSize=" + snapshot.size()
      + ", maxDepth=" + maxDepth
      + ", proxies=" + proxies
      + ", missingNames=" + missingNames
      + ", missingElementIds=" + missingElementIds
      + ", danglingContained=" + missingContainers
      + ", resource=" + (root.eResource() == null ? "null" : root.eResource().getClass().getSimpleName())
      + ", kinds=[" + topKinds + "]";
  }

  static boolean isImpliedEObject(EObject object) {
    if (object == null) {
      return false;
    }
    EStructuralFeature isImplied = object.eClass().getEStructuralFeature("isImplied");
    if (isImplied != null && !isImplied.isDerived()) {
      Object value = object.eGet(isImplied);
      if (Boolean.TRUE.equals(value)) {
        return true;
      }
    }
    return false;
  }

  static void clearSerializationOnlyFeatures(EObject object) {
    if (object == null) {
      return;
    }
    // elementId: the Element_elementId_SettingDelegate.isSet() always returns true,
    // so eUnset() is a no-op. Replace the per-instance delegate via reflection so
    // the Xtext serializer sees eIsSet(elementId)=false and skips the feature.
    if (ELEMENT_ID_DELEGATE_FIELD == null) {
      synchronized (SYSML_LOCK) {
        if (ELEMENT_ID_DELEGATE_FIELD == null) {
          ELEMENT_ID_DELEGATE_FIELD = resolveElementIdDelegateField();
        }
      }
    }
    if (ELEMENT_ID_DELEGATE_FIELD != null) {
      try {
        ELEMENT_ID_DELEGATE_FIELD.set(object, ELEMENT_ID_UNSET_DELEGATE);
      } catch (IllegalAccessException ignored) {}
    }
    clearFeature(object, "aliasIds");
    clearFeature(object, "isImpliedIncluded");
  }

  static void clearFeature(EObject object, String featureName) {
    EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
    if (feature == null || feature.isDerived() || !object.eIsSet(feature)) {
      return;
    }
    if (feature.isMany()) {
      Object value = object.eGet(feature);
      if (value instanceof List<?>) {
        ((List<?>) value).clear();
      } else {
        try {
          object.eUnset(feature);
        } catch (UnsupportedOperationException ignored) {
        }
      }
      return;
    }
    try {
      object.eUnset(feature);
    } catch (UnsupportedOperationException ignored) {
    }
  }

  static JsonArray buildCommitChangePayload(Element rootElement) {
    JsonElementProcessingFacade facade = new JsonElementProcessingFacade();
    facade.setTraversal(new Traversal(facade));
    facade.getTraversal().visit(rootElement);
    return facade.toJsonTree(true).getAsJsonArray();
  }

  static Element resolveLoadedElement(SysMLInteractive sysml, String elementName) {
    return TEXTUAL_MODEL_SERVICE.resolveLoadedElement(sysml, elementName);
  }

  static Element resolveLoadedElement(
    SysMLInteractive sysml,
    String elementName,
    String rootNamespaceId,
    String rootNamespaceName
  ) {
    return TEXTUAL_MODEL_SERVICE.resolveLoadedElement(sysml, elementName, rootNamespaceId, rootNamespaceName);
  }

  static Element resolveTopLevelLoadedElement(SysMLInteractive sysml) {
    return TEXTUAL_MODEL_SERVICE.resolveTopLevelLoadedElement(sysml);
  }

  static int containmentDepthBelow(EObject root, EObject candidate) {
    int depth = 0;
    EObject current = candidate;
    while (current != null && current != root) {
      current = current.eContainer();
      depth++;
    }
    return current == root ? depth : -1;
  }

  static int elementSelectionScore(Element element) {
    return TEXTUAL_MODEL_SERVICE.elementSelectionScore(element);
  }

  static int compareElementPreference(Element left, Element right) {
    return TEXTUAL_MODEL_SERVICE.compareElementPreference(left, right);
  }

  static TextualModelService.TextualLoadRequest textualLoadRequestFromQuery(Map<String, String> q) {
    return new TextualModelService.TextualLoadRequest(
      apiBaseFromRequest(q.get("apiBase")),
      firstNonBlank(q.get("projectName")),
      firstNonBlank(q.get("projectId")),
      firstNonBlank(q.get("branchName")),
      firstNonBlank(q.get("branchId")),
      firstNonBlank(q.get("element")),
      firstNonBlank(q.get("rootNamespaceId")),
      firstNonBlank(q.get("rootNamespaceName"))
    );
  }

  static TextualModelService.TextualCommitRequest textualCommitRequestFromJson(HttpExchange ex, JsonObject request) {
    return new TextualModelService.TextualCommitRequest(
      apiBaseFromRequest(jsonString(request, "apiBase")),
      firstNonBlank(jsonString(request, "projectName")),
      firstNonBlank(jsonString(request, "projectId")),
      firstNonBlank(jsonString(request, "branchName")),
      firstNonBlank(jsonString(request, "branchId")),
      firstNonBlank(jsonString(request, "modelText")),
      firstNonBlank(jsonString(request, "commitMessage"), DEFAULT_COMMIT_MESSAGE),
      bearerTokenFromRequest(ex, request)
    );
  }

  static int containmentDepth(EObject object) {
    int depth = 0;
    EObject current = object;
    while (current != null) {
      current = current.eContainer();
      depth++;
    }
    return depth;
  }

  static JsonObject issueJson(Issue issue) {
    JsonObject obj = new JsonObject();
    obj.addProperty("severity", issue.getSeverity() == null ? "" : issue.getSeverity().toString());
    obj.addProperty("message", firstNonBlank(issue.getMessage()));
    obj.addProperty("line", issue.getLineNumber() == null ? -1 : issue.getLineNumber());
    obj.addProperty("column", issue.getColumn() == null ? -1 : issue.getColumn());
    obj.addProperty("offset", issue.getOffset() == null ? -1 : issue.getOffset());
    obj.addProperty("length", issue.getLength() == null ? -1 : issue.getLength());
    obj.addProperty("uriToProblem", issue.getUriToProblem() == null ? "" : issue.getUriToProblem().toString());
    return obj;
  }

  static JsonArray issuesJson(List<Issue> issues) {
    JsonArray arr = new JsonArray();
    if (issues == null) {
      return arr;
    }
    for (Issue issue : issues) {
      arr.add(issueJson(issue));
    }
    return arr;
  }

  static JsonObject validationJson(SysMLInteractiveResult result) {
    JsonObject json = new JsonObject();
    json.addProperty("ok", result != null && !result.hasErrors() && result.getException() == null);
    json.addProperty("hasErrors", result != null && result.hasErrors());
    json.addProperty("hasWarnings", result != null && result.hasWarnings());
    if (result == null) {
      json.addProperty("exception", "Validation result was null");
      json.add("issues", new JsonArray());
      json.add("syntaxErrors", new JsonArray());
      json.add("semanticErrors", new JsonArray());
      json.add("warnings", new JsonArray());
      return json;
    }
    json.addProperty("rootElementId",
      result.getRootElement() == null ? "" : firstNonBlank(result.getRootElement().getElementId()));
    json.addProperty("rootElementName",
      result.getRootElement() == null ? "" : safeDisplayName(result.getRootElement()));
    json.add("issues", issuesJson(result.getIssues()));
    json.add("syntaxErrors", issuesJson(result.getSyntaxErrors()));
    json.add("semanticErrors", issuesJson(result.getSemanticErrors()));
    json.add("warnings", issuesJson(result.getWarnings()));
    if (result.getException() != null) {
      json.addProperty("exception", firstNonBlank(result.formatException(), result.getException().toString()));
    }
    return json;
  }

  static String describeElement(Element element) {
    if (element == null) {
      return "(null)";
    }
    return "type=" + element.eClass().getName()
      + ", qualifiedName=" + firstNonBlank(safeQualifiedName(element), "(blank)")
      + ", declaredName=" + firstNonBlank(safeDeclaredName(element), safeName(element), "(blank)")
      + ", elementId=" + firstNonBlank(element.getElementId(), "(blank)")
      + ", ownedRelationshipCount=" + (element.getOwnedRelationship() == null ? -1 : element.getOwnedRelationship().size());
  }

  static String describeIssue(Issue issue) {
    if (issue == null) {
      return "(null issue)";
    }
    return "severity=" + (issue.getSeverity() == null ? "" : issue.getSeverity().toString())
      + ", line=" + (issue.getLineNumber() == null ? -1 : issue.getLineNumber())
      + ", column=" + (issue.getColumn() == null ? -1 : issue.getColumn())
      + ", offset=" + (issue.getOffset() == null ? -1 : issue.getOffset())
      + ", length=" + (issue.getLength() == null ? -1 : issue.getLength())
      + ", message=" + firstNonBlank(issue.getMessage());
  }

  static void logIssues(String prefix, List<Issue> issues) {
    if (issues == null || issues.isEmpty()) {
      log(prefix + " none");
      return;
    }
    log(prefix + " count=" + issues.size());
    for (int i = 0; i < issues.size(); i++) {
      log(prefix + "[" + i + "] " + describeIssue(issues.get(i)));
    }
  }

  static void logValidationDetails(String prefix, SysMLInteractive textualSysml, SysMLInteractiveResult result) {
    if (result == null) {
      log(prefix + " result=(null)");
      return;
    }
    if (result.getException() != null) {
      log(prefix + " exception=" + firstNonBlank(result.formatException(), result.getException().toString()));
    }
    log(prefix + " root=" + describeElement(result.getRootElement()));
    try {
      Element partsPart = textualSysml == null ? null : textualSysml.resolve("Parts::Part");
      log(prefix + " library resolve Parts::Part=" + describeElement(partsPart));
    } catch (Exception e) {
      log(prefix + " library resolve Parts::Part failed: " + e);
    }
    logIssues(prefix + " syntax", result.getSyntaxErrors());
    logIssues(prefix + " semantic", result.getSemanticErrors());
    logIssues(prefix + " warnings", result.getWarnings());
    logIssues(prefix + " allIssues", result.getIssues());
  }

  static JsonObject runCommitBridge(
    String apiBase,
    String projectName,
    String projectId,
    String branchName,
    String branchId,
    String commitMessage,
    String bearerToken,
    JsonArray changePayload
  ) throws Exception {
    String token = firstNonBlank(bearerToken);
    if (token.isBlank()) {
      throw new IllegalStateException("Bearer token is not configured");
    }
    log("[commit-bridge] preparing commit bridge request"
      + ": apiBase=" + apiBase
      + ", projectName=" + projectName
      + ", projectId=" + projectId
      + ", branchName=" + branchName
      + ", branchId=" + branchId
      + ", commitMessage=" + commitMessage
      + ", changeCount=" + (changePayload == null ? 0 : changePayload.size()));

    JsonObject commitPayload = new JsonObject();
    commitPayload.addProperty("@type", "Commit");
    commitPayload.addProperty("description", firstNonBlank(commitMessage, DEFAULT_COMMIT_MESSAGE));
    commitPayload.add("change", changePayload);

    Path payloadFile = Files.createTempFile("sysml-text-commit-", ".json");
    Files.writeString(
      payloadFile,
      GSON.toJson(commitPayload),
      StandardCharsets.UTF_8,
      StandardOpenOption.TRUNCATE_EXISTING
    );
    log("[commit-bridge] wrote payload file: " + payloadFile.toAbsolutePath());

    List<String> command = new ArrayList<>();
    command.add(PYTHON_BIN);
    command.add("sysml_replace_commit.py");
    command.add("--api-base");
    command.add(apiBase);
    if (!projectName.isBlank()) {
      command.add("--project-name");
      command.add(projectName);
    }
    if (!projectId.isBlank()) {
      command.add("--project-id");
      command.add(projectId);
    }
    if (!branchName.isBlank()) {
      command.add("--branch-name");
      command.add(branchName);
    }
    if (!branchId.isBlank()) {
      command.add("--branch-id");
      command.add(branchId);
    }
    command.add("--payload-file");
    command.add(payloadFile.toAbsolutePath().toString());

    return runJsonBridgeProcess(
      "[commit-bridge]",
      command,
      token,
      payloadFile,
      "Commit bridge failed",
      "Commit bridge returned non-object JSON"
    );
  }

  static String emptyFormatMessage(VizResult vr, String formatName) {
    StringBuilder msg = new StringBuilder("No ")
      .append(formatName)
      .append(" output was produced.");

    String svg = vr.getSVG();
    if (svg != null && !svg.isBlank()) {
      msg.append(" SVG output is available.");
    }

    String puml = vr.getPlantUML();
    if (puml != null && !puml.isBlank()) {
      msg.append(" PlantUML output is available.");
    }

    String txt = vr.getText();
    if (txt != null && !txt.isBlank()) {
      msg.append(" Text output is available.");
    }

    return msg.toString();
  }

  static VizResult vizResolvedElement(SysMLInteractive sysml, EObject element) {
    return vizResolvedElement(sysml, element, Collections.emptyList(), Collections.emptyList());
  }

  static VizResult vizResolvedElement(
    SysMLInteractive sysml,
    EObject element,
    List<String> views,
    List<String> styles
  ) {
    List<String> requestedStyles = styles == null ? Collections.emptyList() : styles;
    try {
      VizResult result = invokeVizReflective(sysml, element, views, requestedStyles);
      if (shouldRetryWithoutMetadata(result) && !containsStyleIgnoreCase(requestedStyles, "HIDEMETADATA")) {
        List<String> retryStyles = new ArrayList<>(requestedStyles);
        retryStyles.add("HIDEMETADATA");
        log("[render] retrying viz with HIDEMETADATA after metadata feature-chain failure");
        return normalizeVizResult(invokeVizReflective(sysml, element, views, retryStyles));
      }
      return normalizeVizResult(result);
    } catch (Exception e) {
      if (isMetadataFeatureChainFailure(rootCause(e)) && !containsStyleIgnoreCase(requestedStyles, "HIDEMETADATA")) {
        try {
          List<String> retryStyles = new ArrayList<>(requestedStyles);
          retryStyles.add("HIDEMETADATA");
          log("[render] retrying viz with HIDEMETADATA after metadata feature-chain exception");
          return normalizeVizResult(invokeVizReflective(sysml, element, views, retryStyles));
        } catch (Exception retryException) {
          return normalizeVizException(retryException);
        }
      }
      return normalizeVizException(e);
    }
  }

  static VizResult invokeVizReflective(
    SysMLInteractive sysml,
    EObject element,
    List<String> views,
    List<String> styles
  ) throws Exception {
    Method method = sysml.getClass().getDeclaredMethod(
      "viz",
      List.class,
      List.class,
      List.class
    );
    method.setAccessible(true);
    Object result = method.invoke(
      sysml,
      Collections.singletonList(element),
      views,
      styles
    );
    return (VizResult) result;
  }

  static VizResult normalizeVizException(Exception exception) {
    Throwable root = rootCause(exception);
    if (isBrokenFeatureChainName(root)) {
      return VizResult.vizExceptionResult(
        "Visualization hit a malformed feature chain: FeatureChaining.getChainingFeature() returned null."
      );
    }
    if (isMetadataFeatureChainFailure(root)) {
      return VizResult.vizExceptionResult(
        "Visualization hit a malformed metadata feature chain. Retrying with HIDEMETADATA may avoid this Pilot bug."
      );
    }
    return VizResult.exceptionResult(exception);
  }

  static VizResult normalizeVizResult(VizResult result) {
    if (result == null || !result.hasException()) {
      return result;
    }
    String message = result.formatException();
    if (message != null
      && message.contains("FeatureChaining.getChainingFeature()")
      && message.contains("Feature.getName()")) {
      return VizResult.vizExceptionResult(
        "Visualization hit a malformed feature chain: FeatureChaining.getChainingFeature() returned null."
      );
    }
    if (isMetadataFeatureChainFailure(message)) {
      return VizResult.vizExceptionResult(
        "Visualization hit a malformed metadata feature chain. Retrying with HIDEMETADATA may avoid this Pilot bug."
      );
    }
    return result;
  }

  static VizResult vizByElementName(
    SysMLInteractive sysml,
    String elementName,
    List<String> views,
    List<String> styles,
    List<String> help
  ) {
    try {
      VizResult result = sysml.viz(
        Collections.singletonList(elementName),
        views,
        styles,
        help
      );
      return normalizeVizResult(result);
    } catch (Exception e) {
      return normalizeVizException(e);
    }
  }

  static VizResult renderLoadedSelection(
    SysMLInteractive sysml,
    String elementName,
    String rootNamespaceId,
    String rootNamespaceName,
    List<String> viewParams,
    List<String> styleParams
  ) {
    Element resolvedElement = resolveLoadedElement(
      sysml,
      elementName,
      rootNamespaceId,
      rootNamespaceName
    );
    if (resolvedElement == null) {
      throw new IllegalArgumentException(
        "Element not found or not resolvable: "
          + TEXTUAL_MODEL_SERVICE.resolutionTargetSummary(elementName, rootNamespaceId, rootNamespaceName)
      );
    }

    return renderResolvedSelection(sysml, resolvedElement, viewParams, styleParams);
  }

  static VizResult renderResolvedSelection(
    SysMLInteractive sysml,
    Element resolvedElement,
    List<String> viewParams,
    List<String> styleParams
  ) {
    int removedChainings = sanitizeBrokenFeatureChainings(resolvedElement);
    if (removedChainings > 0) {
      log("[render] sanitized " + removedChainings + " broken feature chaining entries");
    }

    return normalizeVizResult(
      vizResolvedElement(sysml, resolvedElement, viewParams, styleParams)
    );
  }

  static int sanitizeBrokenFeatureChainings(EObject root) {
    if (root == null) {
      return 0;
    }
    List<EObject> snapshot = collectEObjectSnapshot(root);
    int removed = 0;
    for (EObject object : snapshot) {
      removed += sanitizeBrokenFeatureChainingsOnObject(object);
    }
    return removed;
  }

  static List<EObject> collectEObjectSnapshot(EObject root) {
    List<EObject> snapshot = new ArrayList<>();
    List<EObject> queue = new ArrayList<>();
    queue.add(root);
    for (int i = 0; i < queue.size(); i++) {
      EObject current = queue.get(i);
      if (current == null) {
        continue;
      }
      snapshot.add(current);
      List<EObject> children = safeEContents(current);
      for (EObject child : children) {
        if (child != null) {
          queue.add(child);
        }
      }
    }
    return snapshot;
  }

  static List<EObject> safeEContents(EObject object) {
    List<EObject> snapshot = new ArrayList<>();
    if (object == null) {
      return snapshot;
    }
    try {
      List<EObject> contents = object.eContents();
      if (contents == null || contents.isEmpty()) {
        return snapshot;
      }
      for (int i = 0; i < contents.size(); i++) {
        try {
          snapshot.add(contents.get(i));
        } catch (RuntimeException ex) {
          break;
        }
      }
    } catch (RuntimeException ex) {
      return snapshot;
    }
    return snapshot;
  }

  static int sanitizeBrokenFeatureChainingsOnObject(EObject object) {
    if (!(object instanceof Feature)) {
      return 0;
    }
    Feature feature = (Feature) object;
    List<FeatureChaining> chainings = feature.getOwnedFeatureChaining();
    if (chainings == null || chainings.isEmpty()) {
      return 0;
    }
    int removed = 0;
    List<FeatureChaining> brokenChainings = new ArrayList<>();
    for (FeatureChaining chaining : chainings) {
      if (chaining == null || safeChainingFeature(chaining) == null) {
        brokenChainings.add(chaining);
      }
    }
    for (FeatureChaining chaining : brokenChainings) {
      if (chaining == null) {
        removed++;
        continue;
      }
      EcoreUtil.remove(chaining);
      if (chainings.contains(chaining)) {
        chainings.remove(chaining);
      }
      removed++;
    }
    return removed;
  }

  static Feature safeChainingFeature(FeatureChaining chaining) {
    if (chaining == null) {
      return null;
    }
    try {
      return chaining.getChainingFeature();
    } catch (NullPointerException ex) {
      return null;
    }
  }

  static final class RenderTimeoutException extends Exception {
    RenderTimeoutException(String message) {
      super(message);
    }
  }

  static <T> T runWithTimeout(String label, Callable<T> task) throws Exception {
    return runWithTimeout(label, task, RENDER_TIMEOUT_MS);
  }

  static <T> T runWithTimeout(String label, Callable<T> task, long timeoutMs) throws Exception {
    Future<T> future = RENDER_EXECUTOR.submit(task);
    try {
      return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException ex) {
      future.cancel(true);
      throw new RenderTimeoutException(
        label + " timed out after " + timeoutMs + " ms"
      );
    } catch (ExecutionException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw new Exception(cause);
    }
  }

  static Throwable rootCause(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  static boolean isBrokenFeatureChainName(Throwable throwable) {
    if (!(throwable instanceof NullPointerException)) {
      return false;
    }
    String message = throwable.getMessage();
    if (message == null) {
      return false;
    }
    return message.contains("FeatureChaining.getChainingFeature()")
      && message.contains("Feature.getName()");
  }

  static boolean isMetadataFeatureChainFailure(Throwable throwable) {
    if (!(throwable instanceof NullPointerException)) {
      return false;
    }
    return isMetadataFeatureChainFailure(throwable.getMessage());
  }

  static boolean isMetadataFeatureChainFailure(String message) {
    if (message == null) {
      return false;
    }
    return message.contains("Feature.getOwnedFeatureChaining()")
      && message.contains(" because \"sf\" is null");
  }

  static boolean shouldRetryWithoutMetadata(VizResult result) {
    return result != null && result.hasException() && isMetadataFeatureChainFailure(result.formatException());
  }

  static boolean containsStyleIgnoreCase(List<String> styles, String target) {
    if (styles == null || target == null) {
      return false;
    }
    for (String style : styles) {
      if (style != null && target.equalsIgnoreCase(style.trim())) {
        return true;
      }
    }
    return false;
  }

  static String requestSummary(
    String apiBase,
    String projectName,
    String projectId,
    String branchName,
    String branchId,
    String elementName,
    String format,
    String view,
    String style
  ) {
    return "apiBase=" + apiBase
      + ", projectName=" + projectName
      + ", projectId=" + projectId
      + ", branchName=" + branchName
      + ", branchId=" + branchId
      + ", element=" + elementName
      + ", format=" + format
      + ", view=" + view
      + ", style=" + style;
  }

  static String compactTextSummary(String value) {
    if (value == null) {
      return "len=0";
    }
    String normalized = value.replaceAll("\\s+", " ").trim();
    if (normalized.length() > 120) {
      normalized = normalized.substring(0, 120) + "...";
    }
    return "len=" + value.length() + ", preview=\"" + normalized + "\"";
  }

  static String summarizeUpstreamErrorBody(String body) {
    String normalized = firstNonBlank(body).trim();
    if (normalized.isBlank()) {
      return "(empty response body)";
    }
    String lower = normalized.toLowerCase();
    if (lower.startsWith("<!doctype html") || lower.startsWith("<html")) {
      if (lower.contains("bad gateway")) {
        return "upstream returned an HTML 502 Bad Gateway page";
      }
      return "upstream returned an HTML error page";
    }
    return compactTextSummary(normalized);
  }

  static JsonArray normalizeElementList(JsonElement parsed) {
    if (parsed != null && parsed.isJsonArray()) {
      return parsed.getAsJsonArray();
    }
    if (parsed != null && parsed.isJsonObject()) {
      JsonElement elements = parsed.getAsJsonObject().get("elements");
      if (elements != null && elements.isJsonArray()) {
        return elements.getAsJsonArray();
      }
    }
    return new JsonArray();
  }

  static boolean isRootNamespace(JsonObject element) {
    return element != null
      && "Namespace".equals(firstNonBlank(jsonString(element, "@type")))
      && (
        !element.has("owningRelationship")
        || element.get("owningRelationship") == null
        || element.get("owningRelationship").isJsonNull()
        || extractReferenceId(element.get("owningRelationship")) == null
      );
  }

  static String rootNamespaceName(JsonObject element) {
    if (element == null) {
      return "<unnamed root namespace>";
    }
    return firstNonBlank(
      jsonString(element, "qualifiedName"),
      jsonString(element, "name"),
      jsonString(element, "@id"),
      "<unnamed root namespace>"
    );
  }

  static String extractReferenceId(JsonElement value) {
    if (value == null || value.isJsonNull()) {
      return null;
    }
    if (value.isJsonObject()) {
      String refId = jsonString(value.getAsJsonObject(), "@id");
      return refId.isBlank() ? null : refId;
    }
    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
      String refId = firstNonBlank(value.getAsString());
      return refId.isBlank() ? null : refId;
    }
    return null;
  }

  static List<String> extractReferenceIds(JsonElement value) {
    List<String> result = new ArrayList<>();
    if (value == null || value.isJsonNull()) {
      return result;
    }
    if (value.isJsonArray()) {
      for (JsonElement item : value.getAsJsonArray()) {
        result.addAll(extractReferenceIds(item));
      }
      return result;
    }
    String refId = extractReferenceId(value);
    if (refId != null) {
      result.add(refId);
    }
    return result;
  }

  static final class RootNamespaceChunk {
    final String rootId;
    final String rootName;
    final JsonArray elements;

    RootNamespaceChunk(String rootId, String rootName, JsonArray elements) {
      this.rootId = rootId;
      this.rootName = rootName;
      this.elements = elements;
    }
  }

  static String resolveRootNamespaceId(
    String elementId,
    Map<String, JsonObject> elementsById,
    Map<String, String> resolvedRootById,
    Set<String> activeIds,
    String[] ownershipReferenceKeys
  ) {
    if (elementId == null || elementId.isBlank()) {
      return null;
    }
    if (resolvedRootById.containsKey(elementId)) {
      return resolvedRootById.get(elementId);
    }
    if (activeIds.contains(elementId)) {
      return null;
    }

    Set<String> nextActiveIds = new HashSet<>(activeIds);
    nextActiveIds.add(elementId);
    List<String> visited = new ArrayList<>();
    String currentId = elementId;
    String rootId = null;

    while (currentId != null) {
      if (resolvedRootById.containsKey(currentId)) {
        rootId = resolvedRootById.get(currentId);
        break;
      }
      if (visited.contains(currentId)) {
        rootId = null;
        break;
      }
      visited.add(currentId);
      JsonObject currentElement = elementsById.get(currentId);
      if (currentElement == null) {
        rootId = null;
        break;
      }
      if (isRootNamespace(currentElement)) {
        rootId = currentId;
        break;
      }

      String owningRelationshipId = extractReferenceId(currentElement.get("owningRelationship"));
      if (owningRelationshipId != null) {
        currentId = owningRelationshipId;
        continue;
      }

      Set<String> candidateRootIds = new HashSet<>();
      for (String key : ownershipReferenceKeys) {
        for (String referencedId : extractReferenceIds(currentElement.get(key))) {
          String candidateRootId = resolveRootNamespaceId(
            referencedId,
            elementsById,
            resolvedRootById,
            nextActiveIds,
            ownershipReferenceKeys
          );
          if (candidateRootId != null) {
            candidateRootIds.add(candidateRootId);
          }
        }
      }
      if (candidateRootIds.size() == 1) {
        rootId = candidateRootIds.iterator().next();
      } else {
        rootId = null;
      }
      break;
    }

    for (String visitedId : visited) {
      resolvedRootById.put(visitedId, rootId);
    }
    return rootId;
  }

  static List<RootNamespaceChunk> splitRootNamespaceDocuments(JsonArray elements) {
    List<JsonObject> rootNamespaces = new ArrayList<>();
    List<Integer> rootIndices = new ArrayList<>();
    for (int i = 0; i < elements.size(); i++) {
      JsonElement entry = elements.get(i);
      if (!entry.isJsonObject()) {
        continue;
      }
      JsonObject element = entry.getAsJsonObject();
      if (isRootNamespace(element)) {
        rootNamespaces.add(element);
        rootIndices.add(i);
      }
    }
    if (rootNamespaces.isEmpty()) {
      throw new IllegalArgumentException("No root namespace found");
    }

    Map<String, Integer> rootIndicesById = new HashMap<>();
    List<String> rootIdsInOrder = new ArrayList<>();
    Map<String, String> rootNamesById = new HashMap<>();
    Map<String, JsonObject> elementsById = new HashMap<>();
    for (int i = 0; i < rootNamespaces.size(); i++) {
      JsonObject root = rootNamespaces.get(i);
      String rootId = firstNonBlank(jsonString(root, "@id"));
      if (!rootId.isBlank()) {
        rootIndicesById.put(rootId, rootIndices.get(i));
        rootIdsInOrder.add(rootId);
        rootNamesById.put(rootId, rootNamespaceName(root));
      }
    }
    for (JsonElement entry : elements) {
      if (!entry.isJsonObject()) {
        continue;
      }
      JsonObject element = entry.getAsJsonObject();
      String elementId = firstNonBlank(jsonString(element, "@id"));
      if (!elementId.isBlank()) {
        elementsById.put(elementId, element);
      }
    }

    String[] ownershipReferenceKeys = new String[] {
      "owningRelatedElement",
      "ownedRelatedElement",
      "memberElement",
      "feature",
      "ownedMember",
      "ownedMemberElement",
      "ownedMemberFeature",
      "ownedElement",
      "owningNamespace",
      "owningType",
      "featuringType",
      "typedFeature"
    };
    Map<String, List<String>> incomingOwnershipRefs = new HashMap<>();
    for (Map.Entry<String, JsonObject> entry : elementsById.entrySet()) {
      String ownerId = entry.getKey();
      JsonObject ownerElement = entry.getValue();
      for (String key : ownershipReferenceKeys) {
        for (String referencedId : extractReferenceIds(ownerElement.get(key))) {
          incomingOwnershipRefs.computeIfAbsent(referencedId, ignored -> new ArrayList<>()).add(ownerId);
        }
      }
    }

    Map<String, String> resolvedRootById = new HashMap<>();
    Map<String, JsonArray> documentElementsByRootId = new HashMap<>();
    for (String rootId : rootIdsInOrder) {
      documentElementsByRootId.put(rootId, new JsonArray());
    }
    List<JsonObject> unassignedElements = new ArrayList<>();

    for (JsonElement entry : elements) {
      if (!entry.isJsonObject()) {
        continue;
      }
      JsonObject element = entry.getAsJsonObject();
      String elementId = extractReferenceId(element.get("@id"));
      if (elementId == null) {
        unassignedElements.add(element);
        continue;
      }
      String rootId = resolveRootNamespaceId(
        elementId,
        elementsById,
        resolvedRootById,
        Collections.emptySet(),
        ownershipReferenceKeys
      );
      if (rootId == null || !documentElementsByRootId.containsKey(rootId)) {
        unassignedElements.add(element);
        continue;
      }
      documentElementsByRootId.get(rootId).add(element);
    }

    boolean changed = true;
    while (changed && !unassignedElements.isEmpty()) {
      changed = false;
      List<JsonObject> stillUnassigned = new ArrayList<>();
      for (JsonObject element : unassignedElements) {
        String elementId = extractReferenceId(element.get("@id"));
        if (elementId == null) {
          stillUnassigned.add(element);
          continue;
        }
        Set<String> candidateRootIds = new HashSet<>();
        for (String ownerId : incomingOwnershipRefs.getOrDefault(elementId, Collections.emptyList())) {
          String candidateRootId = resolveRootNamespaceId(
            ownerId,
            elementsById,
            resolvedRootById,
            Collections.emptySet(),
            ownershipReferenceKeys
          );
          if (candidateRootId != null) {
            candidateRootIds.add(candidateRootId);
          }
        }
        if (candidateRootIds.size() != 1) {
          stillUnassigned.add(element);
          continue;
        }
        String inferredRootId = candidateRootIds.iterator().next();
        documentElementsByRootId.get(inferredRootId).add(element);
        resolvedRootById.put(elementId, inferredRootId);
        changed = true;
      }
      unassignedElements = stillUnassigned;
    }

    List<JsonObject> stillUnassigned = new ArrayList<>();
    for (JsonObject element : unassignedElements) {
      int elementIndex = -1;
      for (int i = 0; i < elements.size(); i++) {
        if (elements.get(i) == element) {
          elementIndex = i;
          break;
        }
      }
      if (elementIndex < 0) {
        stillUnassigned.add(element);
        continue;
      }
      String fallbackRootId = null;
      for (String rootId : rootIdsInOrder) {
        Integer rootIndex = rootIndicesById.get(rootId);
        if (rootIndex != null && rootIndex <= elementIndex) {
          fallbackRootId = rootId;
        } else {
          break;
        }
      }
      if (fallbackRootId == null) {
        stillUnassigned.add(element);
        continue;
      }
      documentElementsByRootId.get(fallbackRootId).add(element);
    }

    if (!stillUnassigned.isEmpty()) {
      List<String> unassignedIds = new ArrayList<>();
      for (JsonObject element : stillUnassigned) {
        String elementId = firstNonBlank(jsonString(element, "@id"));
        if (!elementId.isBlank()) {
          unassignedIds.add(elementId);
        }
      }
      throw new IllegalArgumentException(
        "Could not assign some elements to a root namespace via owningRelationship: "
        + String.join(", ", unassignedIds)
      );
    }

    List<RootNamespaceChunk> documents = new ArrayList<>();
    for (JsonObject rootNamespace : rootNamespaces) {
      String rootId = firstNonBlank(jsonString(rootNamespace, "@id"));
      if (rootId.isBlank()) {
        continue;
      }
      JsonArray documentElements = documentElementsByRootId.get(rootId);
      if (documentElements == null || documentElements.size() == 0) {
        continue;
      }
      int rootPosition = -1;
      for (int i = 0; i < documentElements.size(); i++) {
        JsonElement entry = documentElements.get(i);
        if (!entry.isJsonObject()) {
          continue;
        }
        if (rootId.equals(extractReferenceId(entry.getAsJsonObject().get("@id")))) {
          rootPosition = i;
          break;
        }
      }
      if (rootPosition > 0) {
        JsonElement rootElement = documentElements.remove(rootPosition);
        JsonArray reordered = new JsonArray();
        reordered.add(rootElement);
        for (JsonElement entry : documentElements) {
          reordered.add(entry);
        }
        documentElements = reordered;
      }
      documents.add(new RootNamespaceChunk(rootId, rootNamesById.get(rootId), documentElements));
    }
    return documents;
  }

  static String resolveBranchHeadCommitId(String apiBaseClean, String projectId, String branchId, String bearerToken) throws Exception {
    HttpRequest branchRequest = HttpRequest.newBuilder()
      .uri(URI.create(apiBaseClean + "/projects/" + projectId + "/branches/" + branchId))
      .timeout(java.time.Duration.ofSeconds(30))
      .header("Accept", "application/json")
      .header("Authorization", bearerToken)
      .header("User-Agent", "sysmlv2viz-elements/1.0")
      .GET()
      .build();
    HttpResponse<String> branchResponse = HTTP_CLIENT.send(branchRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (branchResponse.statusCode() < 200 || branchResponse.statusCode() >= 300) {
      throw new IllegalStateException(
        "Branch lookup failed with status " + branchResponse.statusCode()
          + ": " + summarizeUpstreamErrorBody(branchResponse.body())
      );
    }
    JsonObject branchObj = JsonParser.parseString(firstNonBlank(branchResponse.body(), "{}")).getAsJsonObject();
    String commitId = resolveBranchCommitId(branchObj);
    if (commitId == null || commitId.isBlank()) {
      throw new IllegalStateException("Could not resolve latest commit ID from selected branch");
    }
    return commitId;
  }

  static String parseNextCursor(String linkHeader) {
    if (linkHeader == null || linkHeader.isBlank()) {
      return null;
    }
    for (String rel : new String[] {"rel=\"next\"", "rel=next"}) {
      if (!linkHeader.contains(rel)) {
        continue;
      }
      for (String part : linkHeader.split(",")) {
        if (!part.contains(rel)) {
          continue;
        }
        String urlPart = part.split(";", 2)[0].trim();
        if (urlPart.startsWith("<") && urlPart.endsWith(">")) {
          urlPart = urlPart.substring(1, urlPart.length() - 1);
        }
        int queryIndex = urlPart.indexOf('?');
        String query = queryIndex >= 0 ? urlPart.substring(queryIndex + 1) : "";
        for (String qp : query.split("&")) {
          if (qp.startsWith("page.after=")) {
            return URLDecoder.decode(qp.substring("page.after=".length()), StandardCharsets.UTF_8);
          }
        }
      }
    }
    return null;
  }

  static String fetchElementsForBranch(String apiBase, String projectId, String branchId, String bearerToken) throws Exception {
    String apiBaseClean = firstNonBlank(apiBase).replaceAll("/+$", "");
    log("[elements] fetch start: apiBase=" + apiBaseClean
      + ", projectId=" + projectId
      + ", branchId=" + branchId);
    String commitId = resolveBranchHeadCommitId(apiBaseClean, projectId, branchId, bearerToken);
    log("[elements] resolved commitId=" + commitId + " for branchId=" + branchId);

    JsonArray allElements = new JsonArray();
    String nextCursor = null;
    while (true) {
      String url = apiBaseClean + "/projects/" + projectId + "/commits/" + commitId + "/elements";
      if (nextCursor != null && !nextCursor.isBlank()) {
        url += "?page.after=" + URLEncoder.encode(nextCursor, StandardCharsets.UTF_8);
      }
      HttpRequest elementsRequest = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(java.time.Duration.ofSeconds(120))
        .header("Accept", "application/json")
        .header("Authorization", bearerToken)
        .header("User-Agent", "sysmlv2viz-elements/1.0")
        .GET()
        .build();
      HttpResponse<String> elementsResponse = HTTP_CLIENT.send(elementsRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      log("[elements] upstream response: status=" + elementsResponse.statusCode()
        + ", cursor=" + firstNonBlank(nextCursor, "(start)")
        + ", summary=" + summarizeUpstreamErrorBody(elementsResponse.body()));
      if (elementsResponse.statusCode() < 200 || elementsResponse.statusCode() >= 300) {
        throw new IllegalStateException(
          "Elements fetch failed with status " + elementsResponse.statusCode()
          + ": " + summarizeUpstreamErrorBody(elementsResponse.body())
        );
      }
      JsonArray page = normalizeElementList(JsonParser.parseString(firstNonBlank(elementsResponse.body(), "[]")));
      for (JsonElement entry : page) {
        allElements.add(entry);
      }
      String parsedNextCursor = parseNextCursor(elementsResponse.headers().firstValue("Link").orElse(""));
      if (parsedNextCursor == null || parsedNextCursor.isBlank() || page.size() == 0) {
        break;
      }
      nextCursor = parsedNextCursor;
    }
    log("[elements] aggregated element count=" + allElements.size());
    return GSON.toJson(allElements);
  }

  static String fetchTextualForModelJson(String apiBase, String bearerToken, String modelJson) throws Exception {
    String apiBaseClean = firstNonBlank(apiBase).replaceAll("/+$", "");
    if (apiBaseClean.isBlank()) {
      throw new IllegalArgumentException("Missing apiBase");
    }
    log("[textual/fromjson] upstream request: modelJsonLength=" + (modelJson == null ? -1 : modelJson.length())
      + ", preview=" + summarizeUpstreamErrorBody(modelJson));
    HttpRequest.Builder builder = HttpRequest.newBuilder()
      .uri(URI.create(apiBaseClean + "/textual/fromjson"))
      .timeout(java.time.Duration.ofSeconds(120))
      .header("Accept", "text/plain")
      .header("Content-Type", "application/json")
      .header("User-Agent", "sysmlv2viz-textual-fromjson/1.0")
      .POST(HttpRequest.BodyPublishers.ofString(modelJson, StandardCharsets.UTF_8));
    if (bearerToken != null && !bearerToken.isBlank()) {
      builder.header("Authorization", bearerToken);
    }
    HttpResponse<String> response = HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    log("[textual/fromjson] upstream response: status=" + response.statusCode()
      + ", summary=" + summarizeUpstreamErrorBody(response.body()));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(
        "Textual conversion failed with status " + response.statusCode()
          + ": " + summarizeUpstreamErrorBody(response.body())
      );
    }
    return firstNonBlank(response.body());
  }

  static JsonArray normalizeCollectionList(JsonElement parsed, String... keys) {
    if (parsed == null) {
      return new JsonArray();
    }
    if (parsed.isJsonArray()) {
      return parsed.getAsJsonArray();
    }
    if (!parsed.isJsonObject()) {
      return new JsonArray();
    }
    JsonObject obj = parsed.getAsJsonObject();
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      JsonElement value = obj.get(key);
      if (value != null && value.isJsonArray()) {
        return value.getAsJsonArray();
      }
    }
    return new JsonArray();
  }

  static JsonArray normalizeProjectList(JsonElement parsed) {
    JsonArray rawProjects = normalizeCollectionList(parsed, "elements", "items", "projects");

    JsonArray projects = new JsonArray();
    for (JsonElement element : rawProjects) {
      if (!element.isJsonObject()) {
        continue;
      }
      JsonObject project = element.getAsJsonObject();
      JsonObject normalized = new JsonObject();
      normalized.addProperty("id", firstNonBlank(
        project.has("@id") && !project.get("@id").isJsonNull() ? project.get("@id").getAsString() : null,
        project.has("id") && !project.get("id").isJsonNull() ? project.get("id").getAsString() : null
      ));
      normalized.addProperty("name", firstNonBlank(
        project.has("name") && !project.get("name").isJsonNull() ? project.get("name").getAsString() : null,
        "(unnamed)"
      ));
      projects.add(normalized);
    }
    return projects;
  }

  static JsonArray fetchProjects(String apiBase, String bearerToken) throws Exception {
    String resolvedApiBase = firstNonBlank(apiBase);
    if (resolvedApiBase.isBlank()) {
      throw new IllegalArgumentException("Missing apiBase");
    }
    String resolvedToken = firstNonBlank(bearerToken);
    if (resolvedToken.isBlank()) {
      throw new IllegalArgumentException("Missing bearer token");
    }

    HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create(resolvedApiBase.replaceAll("/+$", "") + "/projects"))
      .timeout(java.time.Duration.ofSeconds(60))
      .header("Accept", "application/json")
      .header("Authorization", resolvedToken)
      .header("User-Agent", "sysmlv2viz-project-list/1.0")
      .GET()
      .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException("Project lookup failed with status " + response.statusCode() + ": " + response.body());
    }

    JsonElement parsed = JsonParser.parseString(firstNonBlank(response.body(), "[]"));
    return normalizeProjectList(parsed);
  }

  static JsonArray fetchBranches(String apiBase, String projectId, String bearerToken) throws Exception {
    String resolvedApiBase = firstNonBlank(apiBase);
    if (resolvedApiBase.isBlank()) {
      throw new IllegalArgumentException("Missing apiBase");
    }
    if (projectId == null || projectId.isBlank()) {
      throw new IllegalArgumentException("Missing projectId");
    }
    String resolvedToken = firstNonBlank(bearerToken);
    if (resolvedToken.isBlank()) {
      throw new IllegalArgumentException("Missing bearer token");
    }

    HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create(resolvedApiBase.replaceAll("/+$", "") + "/projects/" + projectId + "/branches"))
      .timeout(java.time.Duration.ofSeconds(60))
      .header("Accept", "application/json")
      .header("Authorization", resolvedToken)
      .header("User-Agent", "sysmlv2viz-branch-list/1.0")
      .GET()
      .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String errorBody = firstNonBlank(response.body());
      if (response.statusCode() == 500) {
        log("[branches] API returned 500 for projectId=" + projectId
          + ", attempting project default branch fallback. Body=" + errorBody);
        JsonArray fallbackBranches = fetchDefaultBranchFromProject(
          resolvedApiBase.replaceAll("/+$", ""),
          projectId,
          resolvedToken
        );
        if (fallbackBranches.size() > 0) {
          log("[branches] project default branch fallback succeeded for projectId=" + projectId);
          return fallbackBranches;
        }
        log("[branches] project default branch fallback returned no branches for projectId=" + projectId);
      }
      throw new IllegalStateException("Branch lookup failed with status " + response.statusCode() + ": " + errorBody);
    }

    // The Flexo list endpoint returns names mismatched with UUIDs (names are shuffled across
    // branch objects). The individual GET /branches/{id} endpoint always returns the correct name.
    // So we collect IDs from the list, then fetch each branch individually.
    JsonElement parsed = JsonParser.parseString(firstNonBlank(response.body(), "[]"));
    JsonArray rawBranches = normalizeCollectionList(parsed, "elements", "items", "branches");

    List<String> branchIds = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();
    for (JsonElement element : rawBranches) {
      if (!element.isJsonObject()) continue;
      JsonObject branch = element.getAsJsonObject();
      String id = firstNonBlank(
        branch.has("@id") && !branch.get("@id").isJsonNull() ? branch.get("@id").getAsString() : null,
        branch.has("id") && !branch.get("id").isJsonNull() ? branch.get("id").getAsString() : null
      );
      if (!id.isBlank() && seenIds.add(id)) {
        branchIds.add(id);
      }
    }

    String apiBaseClean = resolvedApiBase.replaceAll("/+$", "");
    JsonArray branches = new JsonArray();
    for (String branchId : branchIds) {
      HttpRequest detailRequest = HttpRequest.newBuilder()
        .uri(URI.create(apiBaseClean + "/projects/" + projectId + "/branches/" + branchId))
        .timeout(java.time.Duration.ofSeconds(30))
        .header("Accept", "application/json")
        .header("Authorization", resolvedToken)
        .header("User-Agent", "sysmlv2viz-branch-list/1.0")
        .GET()
        .build();
      HttpResponse<String> detailResponse = HTTP_CLIENT.send(detailRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (detailResponse.statusCode() < 200 || detailResponse.statusCode() >= 300) {
        log("[branches] detail fetch failed for id=" + branchId + " status=" + detailResponse.statusCode());
        continue;
      }
      JsonElement detailParsed = JsonParser.parseString(firstNonBlank(detailResponse.body(), "{}"));
      if (!detailParsed.isJsonObject()) continue;
      JsonObject detail = detailParsed.getAsJsonObject();
      String name = firstNonBlank(
        detail.has("name") && !detail.get("name").isJsonNull() ? detail.get("name").getAsString() : null,
        "(unnamed)"
      );
      String commitId = resolveBranchCommitId(detail);
      JsonObject normalized = new JsonObject();
      normalized.addProperty("id", branchId);
      normalized.addProperty("name", name);
      normalized.addProperty("commitId", commitId);
      log("[branches] fetched branch: id=" + branchId + " name=" + name);
      branches.add(normalized);
    }
    return branches;
  }

  static String resolveBranchCommitId(JsonObject branch) {
    if (branch == null) return "";
    if (branch.has("referencedCommit") && branch.get("referencedCommit").isJsonObject()) {
      JsonObject ref = branch.getAsJsonObject("referencedCommit");
      String commitId = firstNonBlank(
        ref.has("@id") && !ref.get("@id").isJsonNull() ? ref.get("@id").getAsString() : null,
        ref.has("id") && !ref.get("id").isJsonNull() ? ref.get("id").getAsString() : null
      );
      if (!commitId.isBlank()) return commitId;
    }
    if (branch.has("head") && branch.get("head").isJsonObject()) {
      JsonObject head = branch.getAsJsonObject("head");
      return firstNonBlank(
        head.has("@id") && !head.get("@id").isJsonNull() ? head.get("@id").getAsString() : null,
        head.has("id") && !head.get("id").isJsonNull() ? head.get("id").getAsString() : null
      );
    }
    return "";
  }

  static JsonArray fetchDefaultBranchFromProject(String apiBase, String projectId, String bearerToken) throws Exception {
    HttpRequest req = HttpRequest.newBuilder()
      .uri(URI.create(apiBase + "/projects/" + projectId))
      .timeout(java.time.Duration.ofSeconds(60))
      .header("Accept", "application/json")
      .header("Authorization", bearerToken)
      .header("User-Agent", "sysmlv2viz-branch-list/1.0")
      .GET()
      .build();
    HttpResponse<String> res = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (res.statusCode() < 200 || res.statusCode() >= 300) {
      log("[branches] project fallback also failed with status " + res.statusCode());
      return new JsonArray();
    }
    JsonElement parsed = JsonParser.parseString(firstNonBlank(res.body(), "{}"));
    if (!parsed.isJsonObject()) return new JsonArray();
    JsonObject project = parsed.getAsJsonObject();
    if (!project.has("defaultBranch") || !project.get("defaultBranch").isJsonObject()) {
      return new JsonArray();
    }
    JsonObject defaultBranch = project.getAsJsonObject("defaultBranch");
    JsonObject normalized = new JsonObject();
    normalized.addProperty("id", firstNonBlank(
      defaultBranch.has("@id") && !defaultBranch.get("@id").isJsonNull() ? defaultBranch.get("@id").getAsString() : null,
      defaultBranch.has("id") && !defaultBranch.get("id").isJsonNull() ? defaultBranch.get("id").getAsString() : null
    ));
    normalized.addProperty("name", firstNonBlank(
      defaultBranch.has("name") && !defaultBranch.get("name").isJsonNull() ? defaultBranch.get("name").getAsString() : null,
      "main"
    ));
    normalized.addProperty("commitId", resolveBranchCommitId(defaultBranch));
    JsonArray result = new JsonArray();
    result.add(normalized);
    return result;
  }

  static JsonObject createBranch(String apiBase, String projectId, String branchName, String fromBranchId, String bearerToken) throws Exception {
    String resolvedApiBase = firstNonBlank(apiBase).replaceAll("/+$", "");
    if (resolvedApiBase.isBlank()) throw new IllegalArgumentException("Missing apiBase");
    if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("Missing projectId");
    if (branchName == null || branchName.isBlank()) throw new IllegalArgumentException("Missing branch name");
    if (fromBranchId == null || fromBranchId.isBlank()) throw new IllegalArgumentException("Missing source branch");
    String resolvedToken = firstNonBlank(bearerToken);
    if (resolvedToken.isBlank()) throw new IllegalArgumentException("Missing bearer token");

    // Resolve the head commit of the source branch
    HttpRequest getRequest = HttpRequest.newBuilder()
      .uri(URI.create(resolvedApiBase + "/projects/" + projectId + "/branches/" + fromBranchId))
      .timeout(java.time.Duration.ofSeconds(60))
      .header("Accept", "application/json")
      .header("Authorization", resolvedToken)
      .header("User-Agent", "sysmlv2viz-branch-create/1.0")
      .GET()
      .build();

    HttpResponse<String> getResponse = HTTP_CLIENT.send(getRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (getResponse.statusCode() < 200 || getResponse.statusCode() >= 300) {
      throw new IllegalStateException("Branch lookup failed with status " + getResponse.statusCode() + ": " + getResponse.body());
    }

    JsonObject sourceBranch = JsonParser.parseString(firstNonBlank(getResponse.body(), "{}")).getAsJsonObject();
    log("[branches/create] source branch GET response: " + getResponse.body());
    String commitId = null;
    if (sourceBranch.has("referencedCommit") && sourceBranch.get("referencedCommit").isJsonObject()) {
      JsonObject ref = sourceBranch.getAsJsonObject("referencedCommit");
      commitId = firstNonBlank(
        ref.has("@id") && !ref.get("@id").isJsonNull() ? ref.get("@id").getAsString() : null
      );
    }
    if (commitId == null && sourceBranch.has("head") && sourceBranch.get("head").isJsonObject()) {
      JsonObject ref = sourceBranch.getAsJsonObject("head");
      commitId = firstNonBlank(
        ref.has("@id") && !ref.get("@id").isJsonNull() ? ref.get("@id").getAsString() : null
      );
    }
    log("[branches/create] resolved commitId=" + commitId + " for fromBranchId=" + fromBranchId);
    if (commitId == null || commitId.isBlank()) {
      throw new IllegalStateException("Could not resolve head commit of source branch");
    }

    JsonObject body = new JsonObject();
    body.addProperty("@type", "Branch");
    body.addProperty("name", branchName);
    JsonObject head = new JsonObject();
    head.addProperty("@id", commitId);
    body.add("head", head);

    log("[branches/create] POST body: " + GSON.toJson(body));
    HttpRequest postRequest = HttpRequest.newBuilder()
      .uri(URI.create(resolvedApiBase + "/projects/" + projectId + "/branches"))
      .timeout(java.time.Duration.ofSeconds(60))
      .header("Accept", "application/json")
      .header("Content-Type", "application/json")
      .header("Authorization", resolvedToken)
      .header("User-Agent", "sysmlv2viz-branch-create/1.0")
      .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
      .build();

    HttpResponse<String> postResponse = HTTP_CLIENT.send(postRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    log("[branches/create] POST status=" + postResponse.statusCode() + " response: " + postResponse.body());
    if (postResponse.statusCode() < 200 || postResponse.statusCode() >= 300) {
      throw new IllegalStateException("Branch create failed with status " + postResponse.statusCode() + ": " + postResponse.body());
    }

    JsonElement parsed = JsonParser.parseString(firstNonBlank(postResponse.body(), "{}"));
    if (!parsed.isJsonObject()) throw new IllegalStateException("Branch create returned non-object JSON");
    return parsed.getAsJsonObject();
  }

  static JsonObject runCreateProjectBridge(
    String apiBase,
    String token,
    String projectName,
    String description
  ) throws Exception {
    if (apiBase == null || apiBase.isBlank()) {
      throw new IllegalArgumentException("Missing apiBase");
    }
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("Missing bearer token");
    }
    if (projectName == null || projectName.isBlank()) {
      throw new IllegalArgumentException("Missing project name");
    }

    List<String> command = new ArrayList<>();
    command.add(PYTHON_BIN);
    command.add("sysml_create_project.py");
    command.add("--api-base");
    command.add(apiBase);
    command.add("--name");
    command.add(projectName);
    command.add("--description");
    command.add(firstNonBlank(description));

    return runJsonBridgeProcess(
      "[project-create]",
      command,
      token,
      null,
      "Project create bridge failed",
      "Project create bridge returned non-object JSON"
    );
  }

  static JsonObject runJsonBridgeProcess(
    String logPrefix,
    List<String> command,
    String token,
    Path cleanupFile,
    String failureMessage,
    String nonObjectMessage
  ) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(new java.io.File("."));
    pb.environment().put("SYSML_API_TOKEN", token);
    log(logPrefix + " starting process: " + String.join(" ", command));
    Process process = pb.start();

    String stdout;
    String stderr;
    try (InputStream out = process.getInputStream();
         InputStream err = process.getErrorStream()) {
      stdout = new String(out.readAllBytes(), StandardCharsets.UTF_8);
      stderr = new String(err.readAllBytes(), StandardCharsets.UTF_8);
    }

    int exitCode = process.waitFor();
    if (!stdout.isBlank()) {
      log(logPrefix + " stdout: " + stdout.trim());
    }
    if (!stderr.isBlank()) {
      log(logPrefix + " stderr: " + stderr.trim());
    }
    if (exitCode != 0) {
      if (cleanupFile != null) {
        log(logPrefix + " preserved payload file for retry/debug: " + cleanupFile.toAbsolutePath());
      }
      throw new IllegalStateException(firstNonBlank(
        classifyBridgeFailure(failureMessage, exitCode, stderr, stdout, cleanupFile),
        stderr.isBlank() ? null : failureMessage + " with exit code " + exitCode + ": " + stderr.trim(),
        stdout.isBlank() ? null : failureMessage + " with exit code " + exitCode + ": " + stdout.trim(),
        failureMessage
      ));
    }

    JsonElement parsed = JsonParser.parseString(firstNonBlank(stdout, "{}"));
    if (!parsed.isJsonObject()) {
      if (cleanupFile != null) {
        log(logPrefix + " preserved payload file for retry/debug: " + cleanupFile.toAbsolutePath());
      }
      throw new IllegalStateException(nonObjectMessage);
    }
    if (cleanupFile != null) {
      Files.deleteIfExists(cleanupFile);
    }
    return parsed.getAsJsonObject();
  }

  static String classifyBridgeFailure(
    String failureMessage,
    int exitCode,
    String stderr,
    String stdout,
    Path cleanupFile
  ) {
    String details = firstNonBlank(stderr, stdout);
    if (details == null) {
      return null;
    }
    String normalized = details.toLowerCase();
    if (normalized.contains("cloudflare 524")
      || normalized.contains("error 524")
      || normalized.contains("cloudflare ray id")
      || normalized.contains("origin web server timed out")
      || normalized.contains("upstream commit timed out")) {
      StringBuilder message = new StringBuilder(
        "Upstream commit timed out at the remote server. The model was parsed locally, but the remote replacement commit did not complete in time."
      );
      if (cleanupFile != null) {
        message.append(" Payload preserved at ").append(cleanupFile.toAbsolutePath()).append('.');
      }
      return message.toString();
    }
    if (normalized.contains("read operation timed out")) {
      StringBuilder message = new StringBuilder(
        "Upstream commit timed out while waiting for the remote replacement commit response."
      );
      if (cleanupFile != null) {
        message.append(" Payload preserved at ").append(cleanupFile.toAbsolutePath()).append('.');
      }
      return message.toString();
    }
    return null;
  }

  public static void main(String[] args) throws Exception {
    ensureSysMLDelegatesRegistered();
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8088"));

    SysMLInteractive sysml = SysMLInteractive.getInstance();

    HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

    // Optional: allow some concurrency, but we’ll synchronize SysMLInteractive usage.
    server.setExecutor(Executors.newFixedThreadPool(8));
    log("SysML viz server: starting on port " + port);
    log("SysML viz server: render timeout ms=" + RENDER_TIMEOUT_MS);
    log("SysML viz server: textual timeout ms=" + TEXTUAL_TIMEOUT_MS);
    log("SysML viz server: commit timeout ms=" + COMMIT_TIMEOUT_MS);
    log("SysML viz server: default api base=" + firstNonBlank(ENV_DEFAULTS.get("apiBase"), "(unset)"));
    log("SysML viz server: ui mode=" + UI_MODE + ", allow ui api override=" + ALLOW_UI_API_OVERRIDE);
    log("SysML viz server: python bridge=" + PYTHON_BIN);
    log("SysML viz server: commit token configured=" + (!firstNonBlank(System.getenv("SYSML_API_TOKEN")).isBlank()));
    String startupLibraryPath = firstNonBlank(SYSML_LIBRARY_PATH, "(unset)");
    log("SysML viz server: library path=" + startupLibraryPath);
    if (!"(unset)".equals(startupLibraryPath)) {
      Path root = Paths.get(startupLibraryPath);
      log("SysML viz server: library exists=" + Files.exists(root)
        + ", isDirectory=" + Files.isDirectory(root)
        + ", hasKernel=" + Files.isDirectory(root.resolve("Kernel Libraries"))
        + ", hasSystems=" + Files.isDirectory(root.resolve("Systems Library"))
        + ", hasDomain=" + Files.isDirectory(root.resolve("Domain Libraries")));
    }

    // Serve the UI
    server.createContext("/", (HttpExchange ex) -> {
      try {
        log("[root] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        String requestPath = ex.getRequestURI().getPath();
        if (requestPath.equals("/")) {
          byte[] html = Files.readAllBytes(Path.of("static/index.html"));
          log("[root] served index.html");
          send(ex, 200, "text/html; charset=utf-8", html);
          return;
        }
        if (requestPath.equals("/favicon.ico") || requestPath.equals("/starforge_favicon.png")) {
          byte[] bytes = Files.readAllBytes(Path.of("static/starforge_favicon.png"));
          log("[root] served static/starforge_favicon.png");
          send(ex, 200, "image/png", bytes);
          return;
        }
        String filename = requestPath.replaceFirst("^/", "");
        if (!filename.contains("/") && !filename.contains("..")) {
          Path staticFile = Path.of("static", filename);
          if (Files.exists(staticFile)) {
            byte[] bytes = Files.readAllBytes(staticFile);
            log("[root] served static/" + filename);
            send(ex, 200, staticContentType(filename), bytes);
            return;
          }
        }
        log("[root] not found: " + requestPath);
        sendText(ex, 404, "text/plain; charset=utf-8", "Not found");
      } catch (Exception e) {
        logException("[root] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/health", ex -> {
      log("[health] " + ex.getRequestMethod() + " " + ex.getRequestURI());
      sendText(ex, 200, "text/plain", "ok");
    });

    server.createContext("/config", ex -> {
      log("[config] " + ex.getRequestMethod() + " " + ex.getRequestURI());
      log("[config] response: " + describeConfigSummary());
      sendText(ex, 200, "application/json; charset=utf-8", defaultsJson());
    });

    server.createContext("/projects", (HttpExchange ex) -> {
      try {
        log("[projects] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
          log("[projects] rejected method: " + ex.getRequestMethod());
          sendText(ex, 405, "text/plain; charset=utf-8", "Use POST");
          return;
        }
        String body = readBody(ex);
        if (body == null || body.isBlank()) {
          log("[projects] request rejected: missing body");
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing request body");
          return;
        }

        JsonObject request = parseJsonObject(body);
        String requestApiBase = jsonString(request, "apiBase");
        String apiBase = apiBaseFromRequest(requestApiBase);
        String bearerToken = bearerTokenFromRequest(ex, request);
        log("[projects] request start: apiBase=" + apiBase + ", apiBaseSource=" + apiBaseSource(requestApiBase)
          + ", tokenSource=" + bearerTokenSource(ex, request) + ", tokenConfigured=" + (!bearerToken.isBlank()));

        JsonObject response = runWithTimeout("[projects] lookup", () -> {
          JsonArray projects = fetchProjects(apiBase, bearerToken);
          JsonObject payload = new JsonObject();
          payload.addProperty("count", projects.size());
          payload.add("projects", projects);
          return payload;
        });

        log("[projects] request success: count=" + response.get("count").getAsInt());
        sendText(ex, 200, "application/json; charset=utf-8", GSON.toJson(response));
      } catch (IllegalArgumentException e) {
        log("[projects] request error: " + e.getMessage());
        sendText(ex, 400, "text/plain; charset=utf-8", e.getMessage());
      } catch (IllegalStateException e) {
        log("[projects] request error: " + e.getMessage());
        sendText(ex, 500, "text/plain; charset=utf-8", e.getMessage());
      } catch (RenderTimeoutException e) {
        log("[projects] timeout: " + e.getMessage());
        sendText(ex, 504, "text/plain; charset=utf-8", e.getMessage());
      } catch (Exception e) {
        logException("[projects] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/projects/create", (HttpExchange ex) -> {
      try {
        log("[projects/create] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
          log("[projects/create] rejected method: " + ex.getRequestMethod());
          sendText(ex, 405, "text/plain; charset=utf-8", "Use POST");
          return;
        }
        String body = readBody(ex);
        if (body == null || body.isBlank()) {
          log("[projects/create] request rejected: missing body");
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing request body");
          return;
        }

        JsonObject request = parseJsonObject(body);
        String requestApiBase = jsonString(request, "apiBase");
        String apiBase = apiBaseFromRequest(requestApiBase);
        String bearerToken = bearerTokenFromRequest(ex, request);
        String projectName = firstNonBlank(jsonString(request, "projectName"));
        String description = firstNonBlank(jsonString(request, "description"));
        log("[projects/create] request start: apiBase=" + apiBase
          + ", apiBaseSource=" + apiBaseSource(requestApiBase)
          + ", tokenSource=" + bearerTokenSource(ex, request)
          + ", tokenConfigured=" + (!bearerToken.isBlank())
          + ", projectName=" + projectName);

        JsonObject response = runWithTimeout("[projects/create] create", () ->
          runCreateProjectBridge(apiBase, bearerToken, projectName, description)
        );

        log("[projects/create] request success: id=" + firstNonBlank(
          response.has("@id") && !response.get("@id").isJsonNull() ? response.get("@id").getAsString() : null,
          response.has("id") && !response.get("id").isJsonNull() ? response.get("id").getAsString() : null
        ));
        sendText(ex, 200, "application/json; charset=utf-8", GSON.toJson(response));
      } catch (IllegalArgumentException e) {
        log("[projects/create] request error: " + e.getMessage());
        sendText(ex, 400, "text/plain; charset=utf-8", e.getMessage());
      } catch (IllegalStateException e) {
        log("[projects/create] request error: " + e.getMessage());
        sendText(ex, 500, "text/plain; charset=utf-8", e.getMessage());
      } catch (RenderTimeoutException e) {
        log("[projects/create] timeout: " + e.getMessage());
        sendText(ex, 504, "text/plain; charset=utf-8", e.getMessage());
      } catch (Exception e) {
        logException("[projects/create] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/branches", (HttpExchange ex) -> {
      try {
        log("[branches] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
          sendText(ex, 405, "text/plain; charset=utf-8", "Use POST");
          return;
        }
        String body = readBody(ex);
        if (body == null || body.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing request body");
          return;
        }

        JsonObject request = parseJsonObject(body);
        String requestApiBase = jsonString(request, "apiBase");
        String apiBase = apiBaseFromRequest(requestApiBase);
        String bearerToken = bearerTokenFromRequest(ex, request);
        String projectId = firstNonBlank(jsonString(request, "projectId"));
        log("[branches] apiBase=" + apiBase + ", projectId=" + projectId
          + ", tokenSource=" + bearerTokenSource(ex, request));

        JsonObject response = runWithTimeout("[branches] list", () -> {
          JsonArray branches = fetchBranches(apiBase, projectId, bearerToken);
          JsonObject payload = new JsonObject();
          payload.addProperty("count", branches.size());
          payload.add("branches", branches);
          return payload;
        });

        log("[branches] count=" + response.get("count").getAsInt());
        sendText(ex, 200, "application/json; charset=utf-8", GSON.toJson(response));
      } catch (IllegalArgumentException e) {
        log("[branches] error: " + e.getMessage());
        sendText(ex, 400, "text/plain; charset=utf-8", e.getMessage());
      } catch (IllegalStateException e) {
        log("[branches] error: " + e.getMessage());
        sendText(ex, 500, "text/plain; charset=utf-8", e.getMessage());
      } catch (RenderTimeoutException e) {
        log("[branches] timeout: " + e.getMessage());
        sendText(ex, 504, "text/plain; charset=utf-8", e.getMessage());
      } catch (Exception e) {
        logException("[branches] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/branches/create", (HttpExchange ex) -> {
      try {
        log("[branches/create] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
          sendText(ex, 405, "text/plain; charset=utf-8", "Use POST");
          return;
        }
        String body = readBody(ex);
        if (body == null || body.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing request body");
          return;
        }

        JsonObject request = parseJsonObject(body);
        String requestApiBase = jsonString(request, "apiBase");
        String apiBase = apiBaseFromRequest(requestApiBase);
        String bearerToken = bearerTokenFromRequest(ex, request);
        String projectId = firstNonBlank(jsonString(request, "projectId"));
        String branchName = firstNonBlank(jsonString(request, "branchName"));
        String fromBranchId = firstNonBlank(jsonString(request, "fromBranchId"));
        log("[branches/create] apiBase=" + apiBase + ", projectId=" + projectId
          + ", branchName=" + branchName + ", fromBranchId=" + fromBranchId
          + ", tokenSource=" + bearerTokenSource(ex, request));

        JsonObject response = runWithTimeout("[branches/create] create", () ->
          createBranch(apiBase, projectId, branchName, fromBranchId, bearerToken)
        );

        String newId = firstNonBlank(
          response.has("@id") && !response.get("@id").isJsonNull() ? response.get("@id").getAsString() : null,
          response.has("id") && !response.get("id").isJsonNull() ? response.get("id").getAsString() : null
        );
        log("[branches/create] success: id=" + newId);
        sendText(ex, 200, "application/json; charset=utf-8", GSON.toJson(response));
      } catch (IllegalArgumentException e) {
        log("[branches/create] error: " + e.getMessage());
        sendText(ex, 400, "text/plain; charset=utf-8", e.getMessage());
      } catch (IllegalStateException e) {
        log("[branches/create] error: " + e.getMessage());
        sendText(ex, 500, "text/plain; charset=utf-8", e.getMessage());
      } catch (RenderTimeoutException e) {
        log("[branches/create] timeout: " + e.getMessage());
        sendText(ex, 504, "text/plain; charset=utf-8", e.getMessage());
      } catch (Exception e) {
        logException("[branches/create] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/render", (HttpExchange ex) -> {
      try {
        Map<String, String> q = queryParams(ex.getRequestURI().getRawQuery());

        String requestApiBase = q.get("apiBase");
        String apiBase     = apiBaseFromRequest(requestApiBase);
        String projectName = firstNonBlank(q.get("projectName"));
        String projectId   = firstNonBlank(q.get("projectId"));
        String branchName  = firstNonBlank(q.get("branchName"));
        String branchId    = firstNonBlank(q.get("branchId"));
        String elementName = firstNonBlank(q.get("element"));
        String rootNamespaceId = firstNonBlank(q.get("rootNamespaceId"));
        String rootNamespaceName = firstNonBlank(q.get("rootNamespaceName"));
        String format = firstNonBlank(q.get("format"), "svg").toLowerCase();
        String view = firstNonBlank(q.get("view"));
        String style = firstNonBlank(q.get("style"));
        String requestSummary = requestSummary(
          apiBase,
          projectName,
          projectId,
          branchName,
          branchId,
          elementName,
          format,
          view,
          style
        );

        log("[render] request start: " + requestSummary
          + ", rootNamespaceId=" + firstNonBlank(rootNamespaceId, "(none)")
          + ", rootNamespaceName=" + firstNonBlank(rootNamespaceName, "(none)")
          + ", apiBaseSource=" + apiBaseSource(requestApiBase));


        if (apiBase.isBlank()) {
          log("[render] request rejected: missing apiBase");
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing apiBase");
          return;
        }
        if (projectName.isBlank() && projectId.isBlank()) {
          log("[render] request rejected: missing project identifier");
          sendText(ex, 400, "text/plain; charset=utf-8", "Provide projectName or projectId");
          return;
        }

        Map<String, String> loadParams = new HashMap<>();
        if (!projectId.isBlank())   loadParams.put("id", projectId);
        if (!projectName.isBlank()) loadParams.put("name", projectName);
        if (!branchId.isBlank())    loadParams.put("branch-id", branchId);
        if (!branchName.isBlank())  loadParams.put("branch", branchName);
        List<String> viewParams = view.isBlank()
          ? Collections.emptyList()
          : Collections.singletonList(view);
        List<String> styleParams = style.isBlank()
          ? Collections.emptyList()
          : Collections.singletonList(style);

        VizResult vr = runWithTimeout("[render] model render", () -> {
          synchronized (SYSML_LOCK) {
            SysMLInteractive renderSysml = SysMLInteractive.createInstance();
            renderSysml.setApiBasePath(apiBase);
            log("[render] load start: " + requestSummary);
            String msg = renderSysml.load(loadParams);
            if (msg != null && msg.startsWith("ERROR:")) {
              log("[render] load error: " + msg);
              throw new IllegalStateException(msg);
            }
            log("[render] load complete");

            String resolutionTarget = TEXTUAL_MODEL_SERVICE.resolutionTargetSummary(
              elementName,
              rootNamespaceId,
              rootNamespaceName
            );
            log("[render] resolve start: " + resolutionTarget);
            Element resolvedElement = resolveLoadedElement(
              renderSysml,
              elementName,
              rootNamespaceId,
              rootNamespaceName
            );
            if (resolvedElement == null) {
              log("[render] resolve failed: " + resolutionTarget);
              throw new IllegalArgumentException("Element not found or not resolvable: " + resolutionTarget);
            }
            log("[render] resolve complete: qualifiedName=" + safeDisplayName(resolvedElement));

            log("[render] viz start");
            VizResult result = renderResolvedSelection(
              renderSysml,
              resolvedElement,
              viewParams,
              styleParams
            );
            log("[render] viz complete");
            return result;
          }
        });

        if (vr == null) {
          log("[render] viz returned null");
          sendText(ex, 500, "text/plain; charset=utf-8", "VizResult was null");
          return;
        }
        if (vr.hasException()) {
          log("[render] viz exception: " + vr.formatException());
          sendText(ex, 500, "text/plain; charset=utf-8", vr.formatException());
          return;
        }

        switch (format) {
          case "svg": {
            String svg = vr.getSVG();
            if (svg == null || svg.isBlank()) {
              // helpful fallback
              String puml = vr.getPlantUML();
              log("[render] svg empty");
              sendText(ex, 500, "text/plain; charset=utf-8",
                  "No SVG produced. PlantUML:\n\n" + (puml != null ? puml : "(none)"));
              return;
            }
            log("[render] request success: format=svg");
            sendText(ex, 200, "image/svg+xml; charset=utf-8", svg);
            return;
          }

          case "plantuml":
          case "puml": {
            String puml = vr.getPlantUML();
            if (puml == null || puml.isBlank()) {
              log("[render] plantuml empty");
              sendText(ex, 500, "text/plain; charset=utf-8", emptyFormatMessage(vr, "PlantUML"));
              return;
            }
            log("[render] request success: format=plantuml");
            sendText(ex, 200, "text/plain; charset=utf-8", puml);
            return;
          }

          case "text":
          case "txt": {
            String txt = vr.getText();
            if (txt == null || txt.isBlank()) {
              log("[render] text empty");
              sendText(ex, 500, "text/plain; charset=utf-8", emptyFormatMessage(vr, "text"));
              return;
            }
            log("[render] request success: format=text");
            sendText(ex, 200, "text/plain; charset=utf-8", txt);
            return;
          }

          default:
            log("[render] unsupported format: " + format);
            sendText(ex, 400, "text/plain; charset=utf-8",
                "Unsupported format='" + format + "'. Use svg|plantuml|text");
        }
      } catch (RenderTimeoutException e) {
        log("[render] timeout: " + e.getMessage());
        sendText(ex, 504, "text/plain; charset=utf-8", e.getMessage());
      } catch (IllegalArgumentException e) {
        log("[render] request error: " + e.getMessage());
        sendText(ex, 404, "text/plain; charset=utf-8", e.getMessage());
      } catch (IllegalStateException e) {
        log("[render] request error: " + e.getMessage());
        sendText(ex, 500, "text/plain; charset=utf-8", e.getMessage());
      } catch (Exception e) {
        logException("[render] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/editor", (HttpExchange ex) -> {
      try {
        log("[editor] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        byte[] html = Files.readAllBytes(Path.of("static/editor.html"));
        log("[editor] served editor.html");
        send(ex, 200, "text/html; charset=utf-8", html);
      } catch (Exception e) {
        logException("[editor] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/settings", (HttpExchange ex) -> {
      try {
        log("[settings] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        byte[] html = Files.readAllBytes(Path.of("static/settings.html"));
        log("[settings] served settings.html");
        send(ex, 200, "text/html; charset=utf-8", html);
      } catch (Exception e) {
        logException("[settings] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/solari", (HttpExchange ex) -> {
      try {
        log("[solari] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        byte[] html = Files.readAllBytes(Path.of("static/solari.html"));
        log("[solari] served solari.html");
        send(ex, 200, "text/html; charset=utf-8", html);
      } catch (Exception e) {
        logException("[solari] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/textual", (HttpExchange ex) -> {
      try {
        log("[textual] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
          log("[textual] rejected method: " + ex.getRequestMethod());
          sendText(ex, 405, "text/plain; charset=utf-8", "Use GET");
          return;
        }

        Map<String, String> q = queryParams(ex.getRequestURI().getRawQuery());
        TextualModelService.TextualLoadRequest request = textualLoadRequestFromQuery(q);
        String requestApiBase = q.get("apiBase");
        String apiBase = request.apiBase();
        String projectName = request.projectName();
        String projectId = request.projectId();
        String branchName = request.branchName();
        String branchId = request.branchId();
        String elementName = request.elementName();
        log("[textual] request start: "
          + requestSummary(apiBase, projectName, projectId, branchName, branchId, elementName, "sysml", "", "")
          + ", rootNamespaceId=" + firstNonBlank(request.rootNamespaceId(), "(none)")
          + ", rootNamespaceName=" + firstNonBlank(request.rootNamespaceName(), "(none)")
          + ", apiBaseSource=" + apiBaseSource(requestApiBase));

        if (apiBase.isBlank()) {
          log("[textual] request rejected: missing apiBase");
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing apiBase");
          return;
        }
        if (projectName.isBlank() && projectId.isBlank()) {
          log("[textual] request rejected: missing project identifier");
          sendText(ex, 400, "text/plain; charset=utf-8", "Provide projectName or projectId");
          return;
        }

        String text = runWithTimeout(
          "[textual] serialize",
          () -> TEXTUAL_MODEL_SERVICE.loadAndSerialize(request),
          TEXTUAL_TIMEOUT_MS
        );

        log("[textual] serialize success: " + compactTextSummary(text));
        sendText(ex, 200, "text/plain; charset=utf-8", text);
      } catch (RenderTimeoutException e) {
        log("[textual] timeout: " + e.getMessage());
        sendText(ex, 504, "text/plain; charset=utf-8", e.getMessage());
      } catch (IllegalArgumentException e) {
        log("[textual] request error: " + e.getMessage());
        sendText(ex, 404, "text/plain; charset=utf-8", e.getMessage());
      } catch (IllegalStateException e) {
        log("[textual] request error: " + e.getMessage());
        sendText(ex, 500, "text/plain; charset=utf-8", e.getMessage());
      } catch (Exception e) {
        logException("[textual] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/textual/fromjson", (HttpExchange ex) -> {
      try {
        log("[textual/fromjson] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
          sendText(ex, 405, "text/plain; charset=utf-8", "Use POST");
          return;
        }
        String body = readBody(ex);
        if (body == null || body.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing request body");
          return;
        }
        JsonObject request = parseJsonObject(body);
        String requestApiBase = jsonString(request, "apiBase");
        String apiBase = apiBaseFromRequest(requestApiBase);
        String bearerToken = bearerTokenFromRequest(ex, request);
        String modelJson = firstNonBlank(jsonString(request, "modelJson"));
        if (modelJson.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing modelJson");
          return;
        }
        if (apiBase.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing apiBase");
          return;
        }

        String text = runWithTimeout(
          "[textual/fromjson] serialize",
          () -> fetchTextualForModelJson(apiBase, bearerToken, modelJson),
          TEXTUAL_TIMEOUT_MS
        );

        sendText(ex, 200, "text/plain; charset=utf-8", text);
      } catch (IllegalArgumentException e) {
        log("[textual/fromjson] request error: " + e.getMessage());
        sendText(ex, 400, "text/plain; charset=utf-8", e.getMessage());
      } catch (IllegalStateException e) {
        log("[textual/fromjson] request error: " + e.getMessage());
        sendText(ex, 500, "text/plain; charset=utf-8", e.getMessage());
      } catch (RenderTimeoutException e) {
        log("[textual/fromjson] timeout: " + e.getMessage());
        sendText(ex, 504, "text/plain; charset=utf-8", e.getMessage());
      } catch (Exception e) {
        logException("[textual/fromjson] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/renderText", (HttpExchange ex) -> {
      try {
        log("[renderText] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
          sendText(ex, 405, "text/plain; charset=utf-8", "Use POST");
          return;
        }
        String body = readBody(ex);
        if (body == null || body.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing request body");
          return;
        }
        JsonObject request = parseJsonObject(body);
        String modelText = firstNonBlank(jsonString(request, "modelText"));
        String elementName = firstNonBlank(jsonString(request, "element"));
        String format = firstNonBlank(jsonString(request, "format"), "svg").toLowerCase();
        String view = firstNonBlank(jsonString(request, "view"));
        String style = firstNonBlank(jsonString(request, "style"));
        if (modelText.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing modelText");
          return;
        }
        List<String> viewParams = view.isBlank()
          ? Collections.emptyList()
          : Collections.singletonList(view.trim());
        List<String> styleParams = style.isBlank()
          ? Collections.emptyList()
          : Collections.singletonList(style.trim());

        VizResult vr = runWithTimeout(
          "[renderText] model render",
          () -> TEXTUAL_MODEL_SERVICE.renderProcessedModel(modelText, elementName, viewParams, styleParams)
        );
        if (vr == null) {
          sendText(ex, 500, "text/plain; charset=utf-8", "VizResult was null");
          return;
        }
        if (vr.hasException()) {
          sendText(ex, 500, "text/plain; charset=utf-8", vr.formatException());
          return;
        }

        switch (format) {
          case "svg": {
            String svg = vr.getSVG();
            if (svg == null || svg.isBlank()) {
              sendText(ex, 500, "text/plain; charset=utf-8", emptyFormatMessage(vr, "SVG"));
              return;
            }
            sendText(ex, 200, "image/svg+xml; charset=utf-8", svg);
            return;
          }
          case "plantuml":
          case "puml": {
            String puml = vr.getPlantUML();
            if (puml == null || puml.isBlank()) {
              sendText(ex, 500, "text/plain; charset=utf-8", emptyFormatMessage(vr, "PlantUML"));
              return;
            }
            sendText(ex, 200, "text/plain; charset=utf-8", puml);
            return;
          }
          case "text":
          case "txt": {
            String txt = vr.getText();
            if (txt == null || txt.isBlank()) {
              sendText(ex, 500, "text/plain; charset=utf-8", emptyFormatMessage(vr, "text"));
              return;
            }
            sendText(ex, 200, "text/plain; charset=utf-8", txt);
            return;
          }
          default:
            sendText(ex, 400, "text/plain; charset=utf-8",
              "Unsupported format='" + format + "'. Use svg|plantuml|text");
        }
      } catch (RenderTimeoutException e) {
        log("[renderText] timeout: " + e.getMessage());
        sendText(ex, 504, "text/plain; charset=utf-8", e.getMessage());
      } catch (IllegalArgumentException e) {
        log("[renderText] request error: " + e.getMessage());
        sendText(ex, 400, "text/plain; charset=utf-8", e.getMessage());
      } catch (Exception e) {
        logException("[renderText] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/textual/validate", (HttpExchange ex) -> {
      try {
        log("[textual/validate] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
          log("[textual/validate] rejected method: " + ex.getRequestMethod());
          sendText(ex, 405, "text/plain; charset=utf-8", "Use POST");
          return;
        }
        String body = readBody(ex);
        if (body == null || body.isBlank()) {
          log("[textual/validate] request rejected: missing body");
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing request body");
          return;
        }
        JsonObject request = parseJsonObject(body);
        String modelText = firstNonBlank(jsonString(request, "modelText"));
        if (modelText.isBlank()) {
          log("[textual/validate] request rejected: missing modelText");
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing modelText");
          return;
        }
        log("[textual/validate] parse start: " + compactTextSummary(modelText));

        JsonObject response = runWithTimeout("[textual/validate] parse", () -> {
          return TEXTUAL_MODEL_SERVICE.validateModel("[textual/validate]", modelText);
        });

        log("[textual/validate] parse complete: ok="
          + response.get("ok").getAsBoolean()
          + ", hasErrors=" + response.get("hasErrors").getAsBoolean()
          + ", hasWarnings=" + response.get("hasWarnings").getAsBoolean());
        sendText(ex, 200, "application/json; charset=utf-8", GSON.toJson(response));
      } catch (IllegalArgumentException e) {
        log("[textual/validate] request error: " + e.getMessage());
        sendText(ex, 400, "text/plain; charset=utf-8", e.getMessage());
      } catch (RenderTimeoutException e) {
        log("[textual/validate] timeout: " + e.getMessage());
        sendText(ex, 504, "text/plain; charset=utf-8", e.getMessage());
      } catch (Exception e) {
        logException("[textual/validate] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/textual/commit", (HttpExchange ex) -> {
      try {
        log("[textual/commit] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
          log("[textual/commit] rejected method: " + ex.getRequestMethod());
          sendText(ex, 405, "text/plain; charset=utf-8", "Use POST");
          return;
        }
        String body = readBody(ex);
        if (body == null || body.isBlank()) {
          log("[textual/commit] request rejected: missing body");
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing request body");
          return;
        }
        JsonObject request = parseJsonObject(body);
        TextualModelService.TextualCommitRequest commitRequest = textualCommitRequestFromJson(ex, request);
        String requestApiBase = jsonString(request, "apiBase");
        String apiBase = commitRequest.apiBase();
        String projectName = commitRequest.projectName();
        String projectId = commitRequest.projectId();
        String branchName = commitRequest.branchName();
        String branchId = commitRequest.branchId();
        String modelText = commitRequest.modelText();
        String commitMessage = commitRequest.commitMessage();
        log("[textual/commit] request start: "
          + requestSummary(apiBase, projectName, projectId, branchName, branchId, "", "commit", "", "")
          + ", apiBaseSource=" + apiBaseSource(requestApiBase)
          + ", tokenSource=" + bearerTokenSource(ex, request));
        log("[textual/commit] model summary: " + compactTextSummary(modelText));
        log("[textual/commit] commit message: " + commitMessage);

        if (apiBase.isBlank()) {
          log("[textual/commit] request rejected: missing apiBase");
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing apiBase");
          return;
        }
        if (projectName.isBlank() && projectId.isBlank()) {
          log("[textual/commit] request rejected: missing project identifier");
          sendText(ex, 400, "text/plain; charset=utf-8", "Provide projectName or projectId");
          return;
        }
        if (modelText.isBlank()) {
          log("[textual/commit] request rejected: missing modelText");
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing modelText");
          return;
        }

        JsonObject response = runWithTimeout(
          "[textual/commit] commit",
          () -> TEXTUAL_MODEL_SERVICE.commitModel(commitRequest),
          COMMIT_TIMEOUT_MS
        );

        if (!response.get("ok").getAsBoolean()) {
          log("[textual/commit] request failed validation");
          sendText(ex, 400, "application/json; charset=utf-8", GSON.toJson(response));
          return;
        }
        log("[textual/commit] request success: changeCount=" + response.get("changeCount").getAsInt());
        sendText(ex, 200, "application/json; charset=utf-8", GSON.toJson(response));
      } catch (IllegalArgumentException e) {
        log("[textual/commit] request error: " + e.getMessage());
        sendText(ex, 400, "text/plain; charset=utf-8", e.getMessage());
      } catch (IllegalStateException e) {
        log("[textual/commit] request error: " + e.getMessage());
        sendText(ex, 500, "text/plain; charset=utf-8", e.getMessage());
      } catch (RenderTimeoutException e) {
        log("[textual/commit] timeout: " + e.getMessage());
        sendText(ex, 504, "text/plain; charset=utf-8", e.getMessage());
      } catch (Exception e) {
        logException("[textual/commit] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/textual/json", (HttpExchange ex) -> {
      try {
        log("[textual/json] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
          log("[textual/json] rejected method: " + ex.getRequestMethod());
          sendText(ex, 405, "text/plain; charset=utf-8", "Use POST");
          return;
        }
        String body = readBody(ex);
        if (body == null || body.isBlank()) {
          log("[textual/json] request rejected: missing body");
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing request body");
          return;
        }
        JsonObject request = parseJsonObject(body);
        String modelText = firstNonBlank(jsonString(request, "modelText"));
        if (modelText.isBlank()) {
          log("[textual/json] request rejected: missing modelText");
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing modelText");
          return;
        }
        log("[textual/json] parse start, modelTextLength=" + modelText.length());
        JsonArray changePayload = runWithTimeout("[textual/json] export",
          () -> TEXTUAL_MODEL_SERVICE.exportParsedJson(modelText));
        log("[textual/json] export complete: elementCount=" + changePayload.size());
        byte[] responseBytes = GSON.toJson(changePayload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"model.json\"");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, responseBytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(responseBytes); }
      } catch (IllegalArgumentException e) {
        log("[textual/json] request error: " + e.getMessage());
        sendText(ex, 400, "text/plain; charset=utf-8", e.getMessage());
      } catch (RenderTimeoutException e) {
        log("[textual/json] timeout: " + e.getMessage());
        sendText(ex, 504, "text/plain; charset=utf-8", e.getMessage());
      } catch (Exception e) {
        logException("[textual/json] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/elements", (HttpExchange ex) -> {
      try {
        log("[elements] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
          log("[elements] rejected method: " + ex.getRequestMethod());
          sendText(ex, 405, "text/plain; charset=utf-8", "Use POST");
          return;
        }
        String body = readBody(ex);
        if (body == null || body.isBlank()) {
          log("[elements] request rejected: missing body");
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing request body");
          return;
        }
        JsonObject request = parseJsonObject(body);
        String requestApiBase = jsonString(request, "apiBase");
        String apiBase = apiBaseFromRequest(requestApiBase);
        String bearerToken = bearerTokenFromRequest(ex, request);
        String projectId = firstNonBlank(jsonString(request, "projectId"));
        String branchId = firstNonBlank(jsonString(request, "branchId"));
        log("[elements] request: apiBaseSource=" + apiBaseSource(requestApiBase)
          + ", projectId=" + projectId + ", branchId=" + branchId
          + ", tokenSource=" + bearerTokenSource(ex, request));
        if (apiBase.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing apiBase");
          return;
        }
        if (projectId.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing projectId");
          return;
        }
        if (branchId.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing branchId");
          return;
        }
        if (bearerToken.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing bearer token");
          return;
        }
        String elementsBody = fetchElementsForBranch(apiBase, projectId, branchId, bearerToken);
        log("[elements] fetch complete: bodyLength=" + elementsBody.length());
        byte[] responseBytes = elementsBody.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"elements.json\"");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, responseBytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(responseBytes); }
      } catch (IllegalArgumentException e) {
        log("[elements] request error: " + e.getMessage());
        sendText(ex, 400, "text/plain; charset=utf-8", e.getMessage());
      } catch (IllegalStateException e) {
        log("[elements] request error: " + e.getMessage());
        sendText(ex, 502, "text/plain; charset=utf-8", e.getMessage());
      } catch (Exception e) {
        logException("[elements] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/elements/roots", (HttpExchange ex) -> {
      try {
        log("[elements/roots] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
          sendText(ex, 405, "text/plain; charset=utf-8", "Use POST");
          return;
        }
        String body = readBody(ex);
        if (body == null || body.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing request body");
          return;
        }
        JsonObject request = parseJsonObject(body);
        String requestApiBase = jsonString(request, "apiBase");
        String apiBase = apiBaseFromRequest(requestApiBase);
        String bearerToken = bearerTokenFromRequest(ex, request);
        String projectId = firstNonBlank(jsonString(request, "projectId"));
        String branchId = firstNonBlank(jsonString(request, "branchId"));
        if (apiBase.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing apiBase");
          return;
        }
        if (projectId.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing projectId");
          return;
        }
        if (branchId.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing branchId");
          return;
        }
        if (bearerToken.isBlank()) {
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing bearer token");
          return;
        }

        String elementsBody = fetchElementsForBranch(apiBase, projectId, branchId, bearerToken);
        JsonArray elements = normalizeElementList(JsonParser.parseString(elementsBody));
        List<RootNamespaceChunk> rootDocuments = splitRootNamespaceDocuments(elements);
        log("[elements/roots] split complete: sourceElementCount=" + elements.size()
          + ", rootCount=" + rootDocuments.size());

        JsonArray roots = new JsonArray();
        for (RootNamespaceChunk chunk : rootDocuments) {
          JsonObject root = new JsonObject();
          root.addProperty("id", chunk.rootId);
          root.addProperty("name", firstNonBlank(chunk.rootName, chunk.rootId));
          root.addProperty("elementCount", chunk.elements.size());
          root.addProperty("modelJson", GSON.toJson(chunk.elements));
          roots.add(root);
          log("[elements/roots] root: id=" + chunk.rootId
            + ", name=" + firstNonBlank(chunk.rootName, chunk.rootId)
            + ", elementCount=" + chunk.elements.size());
        }

        JsonObject response = new JsonObject();
        response.addProperty("count", roots.size());
        response.addProperty("sourceElementCount", elements.size());
        response.add("roots", roots);
        sendText(ex, 200, "application/json; charset=utf-8", GSON.toJson(response));
      } catch (IllegalArgumentException e) {
        log("[elements/roots] request error: " + e.getMessage());
        sendText(ex, 400, "text/plain; charset=utf-8", e.getMessage());
      } catch (IllegalStateException e) {
        log("[elements/roots] request error: " + e.getMessage());
        sendText(ex, 502, "text/plain; charset=utf-8", e.getMessage());
      } catch (Exception e) {
        logException("[elements/roots] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/logs", ex -> {
      try {
        log("[logs] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        long afterId = parseLongOrDefault(queryParams(ex.getRequestURI().getRawQuery()).get("after"), 0L);
        sendText(ex, 200, "application/json; charset=utf-8", logsJson(afterId));
      } catch (Exception e) {
        logException("[logs] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.createContext("/renderJson", (HttpExchange ex) -> {
      try {
        log("[renderJson] " + ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
          log("[renderJson] rejected method: " + ex.getRequestMethod());
          sendText(ex, 405, "text/plain; charset=utf-8", "Use POST");
          return;
        }

        String body = readBody(ex);
        if (body == null || body.isBlank()) {
          log("[renderJson] request rejected: missing body");
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing request body");
          return;
        }

        // Expected body:
        // {
        //   "element": "MyElement",
        //   "modelJson": "{... big json ...}",
        //   "format": "svg"
        // }
        String elementName = jsonStringField(body, "element");
        String modelJson   = jsonStringField(body, "modelJson");
        String format      = jsonStringField(body, "format");
        String view        = jsonStringField(body, "view");
        String style       = jsonStringField(body, "style");
        if (format == null || format.isBlank()) format = "svg";
        format = format.trim().toLowerCase();
        List<String> viewParams = firstNonBlank(view).isBlank()
          ? Collections.emptyList()
          : Collections.singletonList(view.trim());
        List<String> styleParams = firstNonBlank(style).isBlank()
          ? Collections.emptyList()
          : Collections.singletonList(style.trim());

        if (modelJson == null || modelJson.isBlank()) {
          log("[renderJson] request rejected: missing modelJson");
          sendText(ex, 400, "text/plain; charset=utf-8", "Missing modelJson");
          return;
        }
        log("[renderJson] request start: element=" + elementName + ", format=" + format + ", modelJsonLength=" + modelJson.length());

        // Build a reload key from the JSON content so we don't rebuild if identical.
        // (You can swap this for a caller-provided key if you prefer.)
        String loadKey = "json|" + Integer.toHexString(modelJson.hashCode());

        VizResult vr = runWithTimeout("[renderJson] model render", () -> {
          synchronized (SYSML_LOCK) {
            // Only (re)load if JSON changed
            if (LAST_LOAD_KEY == null || !LAST_LOAD_KEY.equals(loadKey)) {
              // You implement this method in SysMLInteractive:
              // - parse modelJson into APIModel
              // - EMFModelRefresher(model, tracker).create()
              // - add resources + addResourceToIndex
              // - update tracker shadowing rules as in load(RemoteBranch)
              String msg = loadJsonModelOrError(sysml, "jsonModel", modelJson);
              if (msg != null && msg.startsWith("ERROR:")) {
                log("[renderJson] load error: " + msg);
                throw new IllegalStateException(msg);
              }
              LAST_LOAD_KEY = loadKey;
              log("[renderJson] json model loaded");
            }

            return renderLoadedSelection(
              sysml,
              elementName,
              "",
              "",
              viewParams,
              styleParams
            );
          }
        });

        if (vr == null) {
          log("[renderJson] viz returned null");
          sendText(ex, 500, "text/plain; charset=utf-8", "VizResult was null");
          return;
        }
        if (vr.hasException()) {
          log("[renderJson] viz exception: " + vr.formatException());
          sendText(ex, 500, "text/plain; charset=utf-8", vr.formatException());
          return;
        }

        switch (format) {
          case "svg": {
            String svg = vr.getSVG();
            if (svg == null || svg.isBlank()) {
              String puml = vr.getPlantUML();
              log("[renderJson] svg empty");
              sendText(ex, 500, "text/plain; charset=utf-8",
                  "No SVG produced. PlantUML:\n\n" + (puml != null ? puml : "(none)"));
              return;
            }
            log("[renderJson] request success: format=svg");
            sendText(ex, 200, "image/svg+xml; charset=utf-8", svg);
            return;
          }

          case "plantuml":
          case "puml": {
            String puml = vr.getPlantUML();
            if (puml == null || puml.isBlank()) {
              log("[renderJson] plantuml empty");
              sendText(ex, 500, "text/plain; charset=utf-8", emptyFormatMessage(vr, "PlantUML"));
              return;
            }
            log("[renderJson] request success: format=plantuml");
            sendText(ex, 200, "text/plain; charset=utf-8", puml);
            return;
          }

          case "text":
          case "txt": {
            String txt = vr.getText();
            if (txt == null || txt.isBlank()) {
              log("[renderJson] text empty");
              sendText(ex, 500, "text/plain; charset=utf-8", emptyFormatMessage(vr, "text"));
              return;
            }
            log("[renderJson] request success: format=text");
            sendText(ex, 200, "text/plain; charset=utf-8", txt);
            return;
          }

          default:
            log("[renderJson] unsupported format: " + format);
            sendText(ex, 400, "text/plain; charset=utf-8",
                "Unsupported format='" + format + "'. Use svg|puml|text");
        }
      } catch (RenderTimeoutException e) {
        log("[renderJson] timeout: " + e.getMessage());
        sendText(ex, 504, "text/plain; charset=utf-8", e.getMessage());
      } catch (IllegalStateException e) {
        log("[renderJson] request error: " + e.getMessage());
        sendText(ex, 500, "text/plain; charset=utf-8", e.getMessage());
      } catch (Exception e) {
        logException("[renderJson] request exception", e);
        sendText(ex, 500, "text/plain; charset=utf-8", stackTrace(e));
      }
    });

    server.start();
    log("SysML viz server: http://localhost:" + port + "/");
  }
}
