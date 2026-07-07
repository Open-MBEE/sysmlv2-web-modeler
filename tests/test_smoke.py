import json
import os
import re
import shutil
import socket
import subprocess
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
APP_DIR = REPO_ROOT / "app"
TEST_JAVA_DIR = REPO_ROOT / "tests" / "java"
JARS_DIR = REPO_ROOT / "pilot-jars"
JAR_PATHS = sorted(str(path) for path in JARS_DIR.glob("*.jar"))


def get_free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class SysMLVizSmokeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which("javac") is None or shutil.which("java") is None:
            raise unittest.SkipTest("java toolchain is required")

        cls.port = get_free_port()
        cls.build_dir = REPO_ROOT / ".test-build"
        if cls.build_dir.exists():
            shutil.rmtree(cls.build_dir)
        cls.build_dir.mkdir()

        if not JAR_PATHS:
            raise AssertionError("no Pilot jars found under pilot-jars/")

        source_paths = sorted(str(path) for path in APP_DIR.rglob("*.java"))
        if TEST_JAVA_DIR.exists():
            source_paths.extend(sorted(str(path) for path in TEST_JAVA_DIR.rglob("*.java")))
        compile_cmd = [
            "javac",
            "-encoding",
            "windows-1252",
            "-cp",
            os.pathsep.join(JAR_PATHS),
            "-d",
            str(cls.build_dir),
            *source_paths,
        ]
        compile_result = subprocess.run(
            compile_cmd,
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        if compile_result.returncode != 0:
            raise AssertionError(
                "javac failed\nSTDOUT:\n{}\nSTDERR:\n{}".format(
                    compile_result.stdout, compile_result.stderr
                )
            )

        env = os.environ.copy()
        env.update(
            {
                "PORT": str(cls.port),
                "SYSML_API_BASE": "http://sysml-api:8080",
                "SYSML_API_TOKEN": "Bearer test-token",
            }
        )
        classpath = os.pathsep.join([str(cls.build_dir), *JAR_PATHS])
        cls.classpath = classpath
        cls.server = subprocess.Popen(
            ["java", "-cp", classpath, "SysMLVizServer"],
            cwd=APP_DIR,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        cls._wait_for_server()

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "server"):
            cls.server.terminate()
            try:
                cls.server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                cls.server.kill()
                cls.server.wait(timeout=10)
        if hasattr(cls, "build_dir") and cls.build_dir.exists():
            shutil.rmtree(cls.build_dir)

    @classmethod
    def _wait_for_server(cls):
        deadline = time.time() + 20
        last_error = None
        url = f"http://127.0.0.1:{cls.port}/health"
        while time.time() < deadline:
            if cls.server.poll() is not None:
                stdout, stderr = cls.server.communicate(timeout=5)
                raise AssertionError(
                    "server exited early with code {}\nSTDOUT:\n{}\nSTDERR:\n{}".format(
                        cls.server.returncode, stdout, stderr
                    )
                )
            try:
                with urllib.request.urlopen(url, timeout=2) as response:
                    if response.read().decode("utf-8") == "ok":
                        return
            except Exception as exc:  # pragma: no cover - wait loop
                last_error = exc
                time.sleep(0.25)
        raise AssertionError(f"server did not become ready: {last_error}")

    def fetch(self, path, method="GET", data=None):
        request = urllib.request.Request(
            f"http://127.0.0.1:{self.port}{path}",
            method=method,
            data=data,
        )
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, response.read().decode("utf-8"), response.headers
        except urllib.error.HTTPError as exc:
            return exc.code, exc.read().decode("utf-8"), exc.headers

    def run_java_class(self, class_name):
        result = subprocess.run(
            ["java", "-cp", self.classpath, class_name],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(
            result.returncode,
            0,
            "java class {} failed\nSTDOUT:\n{}\nSTDERR:\n{}".format(
                class_name, result.stdout, result.stderr
            ),
        )

    def test_health_endpoint(self):
        status, body, _ = self.fetch("/health")
        self.assertEqual(status, 200)
        self.assertEqual(body, "ok")

    def test_config_endpoint_exposes_env_defaults(self):
        status, body, headers = self.fetch("/config")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get_content_type(), "application/json")
        payload = json.loads(body)
        self.assertEqual(payload["apiBase"], "http://sysml-api:8080")
        self.assertEqual(payload["apiToken"], "Bearer test-token")
        self.assertEqual(payload["uiMode"], "standalone")
        self.assertTrue(payload["showApiBaseField"])
        self.assertTrue(payload["showBearerTokenField"])
        self.assertTrue(payload["allowUiApiOverride"])

    def test_logs_endpoint_returns_startup_log_entries(self):
        status, body, headers = self.fetch("/logs")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get_content_type(), "application/json")
        payload = json.loads(body)
        self.assertIn("lastId", payload)
        self.assertIn("entries", payload)
        self.assertTrue(any("SysML viz server:" in entry["message"] for entry in payload["entries"]))

    def test_root_page_contains_bootstrap_logic(self):
        status, body, headers = self.fetch("/")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get_content_type(), "text/html")
        self.assertIn('fetch("config"', body)
        self.assertIn('fetch("projects"', body)
        self.assertIn('fetch("branches"', body)
        self.assertIn('fetch("elements/roots"', body)
        self.assertIn('fetch("logs?after=" + encodeURIComponent(String(lastLogId))', body)
        self.assertIn('fetch("render?" + qs.toString())', body)
        self.assertIn('qs.set("rootNamespaceId", rootNamespaceId);', body)
        self.assertIn('qs.set("rootNamespaceName", rootEntry.name);', body)
        self.assertNotIn('fetch("renderText"', body)
        self.assertNotIn('fetch("textual/fromjson"', body)
        self.assertIn('id="projectSelect"', body)
        self.assertNotIn('id="projectId"', body)
        self.assertIn('id="branchSelect"', body)
        self.assertIn('id="rootNamespaceSelect"', body)
        self.assertIn('id="loadRootsBtn"', body)
        self.assertNotIn('id="branch"', body)
        self.assertIn('id="loadBranchesBtn"', body)
        self.assertIn('id="copySelectionUrlBtn"', body)
        self.assertIn("navigator.clipboard.writeText", body)
        self.assertIn("window.prompt(\"Clipboard access is unavailable. Copy this URL:\", url);", body)
        self.assertIn("option.dataset.commitId = branch.commitId || \"\";", body)
        self.assertIn("/projects/${encodeURIComponent(projectId)}/commits/${encodeURIComponent(commitId)}", body)
        self.assertNotIn('id="createBranchBtn"', body)
        self.assertNotIn('id="newBranchName"', body)
        self.assertIn('id="element"', body)
        self.assertNotIn('id="baseField"', body)
        self.assertNotIn('id="tokenField"', body)
        self.assertIn('id="navSettings"', body)
        self.assertIn('id="view"', body)
        self.assertIn('id="style"', body)

    def test_editor_page_contains_textual_controls(self):
        status, body, headers = self.fetch("/editor")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get_content_type(), "text/html")
        self.assertIn('id="modelText"', body)
        self.assertIn('id="projectSelect"', body)
        self.assertNotIn('id="projectId"', body)
        self.assertIn('id="branchSelect"', body)
        self.assertNotIn('id="branch"', body)
        self.assertIn('id="rootNamespaceSelect"', body)
        self.assertIn('id="loadRootsBtn"', body)
        self.assertIn('id="loadBranchesBtn"', body)
        self.assertIn('id="copySelectionUrlBtn"', body)
        self.assertIn("navigator.clipboard.writeText", body)
        self.assertIn("window.prompt(\"Clipboard access is unavailable. Copy this URL:\", url);", body)
        self.assertIn("option.dataset.commitId = branch.commitId || \"\";", body)
        self.assertIn("/projects/${encodeURIComponent(projectId)}/commits/${encodeURIComponent(commitId)}", body)
        self.assertIn('id="createBranchBtn"', body)
        self.assertIn('id="newBranchName"', body)
        self.assertIn('id="newProjectName"', body)
        self.assertIn('id="newProjectDescription"', body)
        self.assertIn('id="createProjectBtn"', body)
        self.assertIn('id="rootNamespaceSelect"', body)
        self.assertIn('id="loadRootsBtn"', body)
        self.assertNotIn('id="element"', body)
        self.assertNotIn('id="baseField"', body)
        self.assertNotIn('id="tokenField"', body)
        self.assertIn('id="navSettings"', body)
        self.assertIn('textual/validate', body)
        self.assertIn('textual/commit', body)
        self.assertIn('fetch("textual?" + qs.toString()', body)
        self.assertIn('qs.set("rootNamespaceId", rootNamespaceId);', body)
        self.assertIn('qs.set("rootNamespaceName", rootEntry.name);', body)
        self.assertNotIn('fetch("textual/fromjson"', body)
        self.assertIn('projects/create', body)
        self.assertIn('branches/create', body)
        self.assertIn("Commit Replace", body)

    def test_settings_page_contains_shared_connection_controls(self):
        status, body, headers = self.fetch("/settings")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get_content_type(), "text/html")
        self.assertIn('id="baseField"', body)
        self.assertIn('id="tokenField"', body)
        self.assertIn('id="navTextEditor"', body)
        self.assertIn('id="navDiagramViewer"', body)
        self.assertIn('id="navSettings"', body)
        self.assertIn("Connection Settings", body)
        self.assertIn('addEventListener("input", persistValue)', body)
        self.assertIn('addEventListener("change", persistValue)', body)
        self.assertIn('return localStorage.getItem(storageKey) !== null;', body)
        self.assertIn('config.uiMode === "embedded" || config.allowUiApiOverride === false', body)

    def test_projects_requires_body(self):
        status, body, _ = self.fetch("/projects", method="POST")
        self.assertEqual(status, 400)
        self.assertIn("Missing request body", body)

    def test_projects_create_requires_body(self):
        status, body, _ = self.fetch("/projects/create", method="POST")
        self.assertEqual(status, 400)
        self.assertIn("Missing request body", body)

    def test_branches_requires_body(self):
        status, body, _ = self.fetch("/branches", method="POST")
        self.assertEqual(status, 400)
        self.assertIn("Missing request body", body)

    def test_branches_create_requires_body(self):
        status, body, _ = self.fetch("/branches/create", method="POST")
        self.assertEqual(status, 400)
        self.assertIn("Missing request body", body)

    def test_render_and_textual_read_paths_do_not_load_libraries(self):
        source = (APP_DIR / "SysMLVizServer.java").read_text(encoding="utf-8")
        service_source = (APP_DIR / "TextualModelService.java").read_text(encoding="utf-8")

        def block(start_marker, end_marker):
            start = source.index(start_marker)
            end = source.index(end_marker, start)
            return source[start:end]

        render_block = block('server.createContext("/render"', 'server.createContext("/editor"')
        textual_block = block('server.createContext("/textual"', 'server.createContext("/textual/validate"')

        self.assertNotIn("configureLibraries(", render_block)
        self.assertNotIn("configureLibraries(", textual_block)
        self.assertIn("configureLibraries(", service_source)
        self.assertIn("validateModel(", service_source)
        self.assertIn("commitModel(", service_source)

    def test_textual_validate_requires_body(self):
        status, body, _ = self.fetch("/textual/validate", method="POST")
        self.assertEqual(status, 400)
        self.assertIn("Missing request body", body)

    def test_textual_commit_requires_body(self):
        status, body, _ = self.fetch("/textual/commit", method="POST")
        self.assertEqual(status, 400)
        self.assertIn("Missing request body", body)

    def test_textual_json_requires_body(self):
        status, body, _ = self.fetch("/textual/json", method="POST")
        self.assertEqual(status, 400)
        self.assertIn("Missing request body", body)

    def test_textual_json_requires_model_text(self):
        data = json.dumps({}).encode("utf-8")
        status, body, _ = self.fetch("/textual/json", method="POST", data=data)
        self.assertEqual(status, 400)
        self.assertIn("Missing modelText", body)

    def test_textual_fromjson_requires_body(self):
        status, body, _ = self.fetch("/textual/fromjson", method="POST")
        self.assertEqual(status, 400)
        self.assertIn("Missing request body", body)

    def test_textual_fromjson_requires_model_json(self):
        data = json.dumps({}).encode("utf-8")
        status, body, _ = self.fetch("/textual/fromjson", method="POST", data=data)
        self.assertEqual(status, 400)
        self.assertIn("Missing modelJson", body)

    def test_render_text_requires_body(self):
        status, body, _ = self.fetch("/renderText", method="POST")
        self.assertEqual(status, 400)
        self.assertIn("Missing request body", body)

    def test_render_text_requires_model_text(self):
        data = json.dumps({}).encode("utf-8")
        status, body, _ = self.fetch("/renderText", method="POST", data=data)
        self.assertEqual(status, 400)
        self.assertIn("Missing modelText", body)

    def test_elements_requires_body(self):
        status, body, _ = self.fetch("/elements", method="POST")
        self.assertEqual(status, 400)
        self.assertIn("Missing request body", body)

    def test_elements_requires_project_and_branch_id(self):
        data = json.dumps({"apiBase": "http://sysml-api:8080", "bearerToken": "Bearer x"}).encode("utf-8")
        status, body, _ = self.fetch("/elements", method="POST", data=data)
        self.assertEqual(status, 400)
        self.assertIn("Missing projectId", body)

    def test_element_roots_requires_body(self):
        status, body, _ = self.fetch("/elements/roots", method="POST")
        self.assertEqual(status, 400)
        self.assertIn("Missing request body", body)

    def test_element_roots_requires_project_and_branch_id(self):
        data = json.dumps({"apiBase": "http://sysml-api:8080", "bearerToken": "Bearer x"}).encode("utf-8")
        status, body, _ = self.fetch("/elements/roots", method="POST", data=data)
        self.assertEqual(status, 400)
        self.assertIn("Missing projectId", body)

    def test_render_json_returns_clear_runtime_message_when_api_missing(self):
        payload = json.dumps(
            {
                "element": "VehicleDefinition",
                "modelJson": "{}",
                "format": "svg",
            }
        ).encode("utf-8")
        status, body, _ = self.fetch(
            "/renderJson",
            method="POST",
            data=payload,
        )
        self.assertEqual(status, 500)
        self.assertIn("loadJsonModel is not available", body)

    def test_namespace_resolve_global_delegate_handles_missing_library_element(self):
        self.run_java_class(
            "org.omg.sysml.delegate.invocation.NamespaceResolveGlobalInvocationDelegateNpeHarness"
        )

    def test_inherit_key_handles_broken_inherited_lookups(self):
        self.run_java_class("org.omg.sysml.plantuml.InheritKeyNpeHarness")

    def test_viz_resolved_element_normalizes_feature_chain_npe(self):
        self.run_java_class("SysMLVizServerFeatureChainNpeHarness")

    def test_render_timeout_helper_fails_fast(self):
        self.run_java_class("SysMLVizServerTimeoutHarness")

    def test_render_json_viz_path_normalizes_feature_chain_npe(self):
        self.run_java_class("SysMLVizServerRenderJsonFeatureChainNpeHarness")

    def test_feature_chain_sanitizer_removes_broken_entries(self):
        self.run_java_class("SysMLVizServerFeatureChainSanitizerHarness")

    def test_normalize_collection_helpers_accept_supported_wrappers(self):
        self.run_java_class("SysMLVizServerNormalizeCollectionsHarness")

    def test_top_level_resolution_picks_named_element(self):
        self.run_java_class("SysMLVizServerTopLevelResolveHarness")

    def test_resolve_loaded_element_prefers_top_level_richer_match(self):
        self.run_java_class("SysMLVizServerResolveSelectionHarness")

    def test_resolve_loaded_element_prefers_explicit_name_over_root_namespace_id(self):
        self.run_java_class("SysMLVizServerResolveSelectionWithRootHarness")

    def test_resolve_loaded_element_supports_root_namespace_only_fallback(self):
        self.run_java_class("SysMLVizServerResolveRootNamespaceFallbackHarness")

    def test_safe_name_helpers_tolerate_derived_name_npe(self):
        self.run_java_class("SysMLVizServerDerivedNameNpeHarness")

    def test_validation_with_libraries_accepts_part_usage(self):
        self.run_java_class("SysMLVizServerValidationWithLibraryHarness")

    def test_flashlight_model_parses_and_validates_without_errors(self):
        self.run_java_class("SysMLVizServerFlashlightHarness")

    def test_validation_json_shape_is_stable(self):
        self.run_java_class("SysMLVizServerValidationJsonHarness")

    def test_serialize_element_text_falls_back_when_serializer_blows_up(self):
        self.run_java_class("SysMLVizServerSerializeFallbackHarness")

    def test_serialize_element_text_prefers_node_model_text_when_available(self):
        self.run_java_class("SysMLVizServerSerializeNodeModelHarness")

    def test_bridge_runner_handles_failure_modes(self):
        self.run_java_class("SysMLVizServerBridgeRunnerHarness")

    def test_fetch_branches_corrects_flexo_name_mismatch(self):
        self.run_java_class("SysMLVizServerFetchBranchesHarness")

    def test_fetch_branches_accepts_items_wrapped_responses(self):
        self.run_java_class("SysMLVizServerFetchBranchesItemsHarness")

    def test_fetch_branches_falls_back_to_project_default_branch_on_500(self):
        self.run_java_class("SysMLVizServerFetchBranchesFallbackHarness")

    def test_fetch_projects_accepts_wrapped_project_lists(self):
        self.run_java_class("SysMLVizServerFetchProjectsHarness")

    def test_render_selection_uses_requested_element_name(self):
        self.run_java_class("SysMLVizServerRenderSelectionHarness")

    def test_export_parsed_json_returns_element_array(self):
        self.run_java_class("SysMLVizServerExportParsedJsonHarness")

    def test_root_namespace_split_groups_interleaved_documents(self):
        self.run_java_class("SysMLVizServerRootNamespaceSplitHarness")


if __name__ == "__main__":
    unittest.main()
