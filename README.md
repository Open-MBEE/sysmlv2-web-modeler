# SysML v2 Visualization Service

This repository packages a standalone Java web service for SysML v2 graphical rendering and textual editing against a SysML v2 API backend.

The service is designed to run well in containers. The browser only talks to this service. The service talks to the remote SysML API, so it can be deployed behind JupyterLab or another proxy without requiring direct browser access to the SysML backend.

The service supports two deployment modes:

- `standalone`
  The user can provide the backend base URL and bearer token in the UI.
- `embedded`
  Intended for iframe deployment in JupyterLab or a similar shell. The backend base URL and bearer token come from environment variables, and the UI hides those fields.

## What The Service Provides

The service has two browser UIs:

- `/` for the graphical visualizer
- `/editor` for textual load, edit, validate, commit, and project creation

It also exposes HTTP endpoints for:

- graphical rendering
- textual model load/readback
- textual validation
- full replacement commit
- parsed JSON export (local Pilot parse → downloadable DataVersion JSON)
- Flexo element list download (live fetch from the remote SysML API)
- project listing and creation
- branch listing and creation

The Docker image already contains:

- the Java server
- the Pilot jars
- the bundled `sysml.library`
- Python runtime for the commit and project-create bridge scripts

## Implementation Overview

The main pieces are:

1. `app/SysMLVizServer.java`
   HTTP server, routing, logging, render flow, project lookup, bridge execution, and shared helpers.
2. `app/TextualModelService.java`
   Textual load/resolve/serialize logic, top-level fallback resolution, validation, and commit preparation.
3. `app/sysml_replace_commit.py`
   Posts full replacement commits to the remote SysML v2 API.
4. `app/sysml_create_project.py`
   Creates projects through the remote SysML v2 API.
5. `app/static/index.html`
   Graphical viewer UI.
6. `app/static/editor.html`
   Textual editor UI.
7. `pilot-jars/`
   Pilot runtime jars.
8. `sysml.library/`
   Bundled SysML standard library used for textual validation and commit parsing.

## Runtime Behavior

### Graphical rendering

The service:

1. loads a model from a remote project and branch
2. resolves the requested element, or a top-level element if blank
3. renders SVG, PlantUML, or text using the Pilot visualizer

### Textual load

The service:

1. loads a model from a remote project and branch
2. resolves the requested element, or a top-level named element if blank
3. tries Xtext serialization
4. falls back to generated SysML-like text when repository-loaded EMF objects cannot be serialized safely

### Textual validation

The service:

1. loads the bundled standard libraries
2. parses edited SysML text locally
3. returns syntax and semantic diagnostics as JSON

### Textual commit

The service:

1. loads the bundled standard libraries
2. parses and validates the edited text locally
3. converts the parsed root element to the API change payload
4. posts a full replacement commit to the remote SysML v2 API

### Parsed JSON export

The service:

1. loads the bundled standard libraries
2. parses and validates the edited SysML text locally
3. converts the parsed root element to the Flexo DataVersion JSON format
4. returns the JSON array as a downloadable `model.json` file

The output format is identical to the change payload used by the replacement commit flow.

### Flexo element list download

The service:

1. resolves the head commit from the selected branch via the Flexo API
2. fetches all elements for that commit from `GET /projects/{id}/commits/{commitId}/elements`
3. returns the raw Flexo response as a downloadable `elements.json` file

### Project operations

The service can:

- list projects from the remote backend
- create a new project from the textual editor UI

## Endpoints

- `GET /`
  Serves the graphical viewer UI.
- `GET /editor`
  Serves the textual editor UI.
- `GET /health`
  Health check, returns `ok`.
- `GET /config`
  Returns default server-side configuration as JSON.
- `GET /logs`
  Returns in-memory server logs as JSON.
- `POST /projects`
  Lists projects from the remote SysML API.
- `POST /projects/create`
  Creates a project on the remote SysML API.
- `POST /branches`
  Lists branches for a project from the remote SysML API.
- `POST /branches/create`
  Creates a branch from an existing branch on the remote SysML API.
- `GET /render`
  Loads a remote model and renders an element.
- `GET /textual`
  Loads a remote model and returns textual SysML for an element.
- `POST /textual/validate`
  Parses and validates edited SysML text.
- `POST /textual/commit`
  Parses, validates, builds change payload, and posts a replacement commit.
- `POST /textual/json`
  Parses SysML text locally and returns the DataVersion JSON as a downloadable file.
- `POST /elements`
  Resolves the branch head commit and downloads all elements from the Flexo API as JSON.
- `POST /renderJson`
  Intended for JSON-backed rendering. This path is still limited by the current Pilot jars because `SysMLInteractive.loadJsonModel(...)` is not present in the shipped runtime.

## Endpoint Details

### `POST /projects`

Request body:

```json
{
  "apiBase": "https://experimental.starforge.app/",
  "bearerToken": "Bearer ..."
}
```

Response:

```json
{
  "count": 2,
  "projects": [
    { "id": "uuid-1", "name": "Project A" },
    { "id": "uuid-2", "name": "Project B" }
  ]
}
```

### `POST /projects/create`

Request body:

```json
{
  "apiBase": "https://experimental.starforge.app/",
  "bearerToken": "Bearer ...",
  "projectName": "test_rka_002",
  "description": "A project created via the editor"
}
```

### `POST /branches`

Request body:

```json
{
  "apiBase": "https://experimental.starforge.app/",
  "bearerToken": "Bearer ...",
  "projectId": "uuid-of-project"
}
```

Response:

```json
{
  "count": 2,
  "branches": [
    { "id": "uuid-1", "name": "Initial" },
    { "id": "uuid-2", "name": "feature-x" }
  ]
}
```

### `POST /branches/create`

Request body:

```json
{
  "apiBase": "https://experimental.starforge.app/",
  "bearerToken": "Bearer ...",
  "projectId": "uuid-of-project",
  "branchName": "my-new-branch",
  "fromBranchId": "uuid-of-source-branch"
}
```

Returns the created branch object from the remote API.

### `GET /render`

Query parameters:

- `apiBase`
- `projectId` or `projectName`
- `branchId` or `branchName`
- `element`
- `format=svg|plantuml|text`
- `view`
- `style`

Example:

```text
/render?apiBase=https://experimental.starforge.app/&projectId=...&element=pkgA&format=svg
```

### `GET /textual`

Query parameters:

- `apiBase`
- `projectId` or `projectName`
- `branchId` or `branchName`
- `element`

If `element` is blank, the service selects a top-level named element.

Example:

```text
/textual?apiBase=https://experimental.starforge.app/&projectId=...&element=pkgA
```

### `POST /textual/validate`

Request body:

```json
{
  "modelText": "package pkgA { part p; }"
}
```

Response fields include:

- `ok`
- `hasErrors`
- `hasWarnings`
- `issues`
- `syntaxErrors`
- `semanticErrors`
- `warnings`
- root element metadata when available

### `POST /textual/commit`

Request body:

```json
{
  "apiBase": "https://experimental.starforge.app/",
  "bearerToken": "Bearer ...",
  "projectId": "...",
  "branchName": "",
  "modelText": "package pkgA { part p; }",
  "commitMessage": "Replace model from textual editor"
}
```

This posts a full-model replacement commit, not a patch.

### `POST /textual/json`

Request body:

```json
{
  "modelText": "package pkgA { part p; }"
}
```

Response: HTTP 200, `Content-Type: application/json`, `Content-Disposition: attachment; filename="model.json"`.

The body is a JSON array of DataVersion objects — the same format as the change payload used by `POST /textual/commit`. Returns HTTP 400 if the model text is missing or has parse errors.

### `POST /elements`

Request body:

```json
{
  "apiBase": "https://experimental.starforge.app/",
  "bearerToken": "Bearer ...",
  "projectId": "uuid-of-project",
  "branchId": "uuid-of-branch"
}
```

Response: HTTP 200, `Content-Type: application/json`, `Content-Disposition: attachment; filename="elements.json"`.

The body is the raw Flexo API response from `GET /projects/{projectId}/commits/{commitId}/elements`. The server resolves the head commit from the branch automatically. Returns HTTP 400 for missing parameters or HTTP 502 if the Flexo API call fails.

## Retrieving an SVG Programmatically (Python)

The following example shows how to look up a project and branch by name and then fetch a rendered SVG diagram using the `requests` library.

### How auth works

`GET /render` does not accept a bearer token. The Pilot library's internal `load()` call handles backend authentication using the API base URL directly. `SYSML_API_TOKEN` is used only by the server-side Python bridge scripts that handle mutation operations (project creation, replacement commit) — it is not involved in model loading or rendering.

The bearer token you pass to `POST /projects` and `POST /branches` is forwarded to the remote SysML API for those calls only.

### Example

```python
import requests

SERVER = "http://localhost:8088"            # base URL of the sysmlviz server
FLEXO_API = "https://experimental.starforge.app/"
BEARER_TOKEN = "Bearer eyJ..."             # your Flexo JWT


def get_project_id(project_name: str) -> str:
    """Resolve a project name to its UUID."""
    resp = requests.post(
        f"{SERVER}/projects",
        json={"apiBase": FLEXO_API, "bearerToken": BEARER_TOKEN},
        timeout=30,
    )
    resp.raise_for_status()
    projects = resp.json()["projects"]
    match = next((p for p in projects if p.get("name") == project_name), None)
    if match is None:
        raise ValueError(f"Project '{project_name}' not found")
    return match["id"]


def get_branch_id(project_id: str, branch_name: str) -> str:
    """Resolve a branch name to its UUID within a project."""
    resp = requests.post(
        f"{SERVER}/branches",
        json={"apiBase": FLEXO_API, "bearerToken": BEARER_TOKEN, "projectId": project_id},
        timeout=30,
    )
    resp.raise_for_status()
    branches = resp.json()["branches"]
    match = next((b for b in branches if b.get("name") == branch_name), None)
    if match is None:
        raise ValueError(f"Branch '{branch_name}' not found in project {project_id}")
    return match["id"]


def get_svg(project_id: str, branch_id: str, element: str = "") -> str:
    """
    Fetch the rendered SVG for an element in a given project and branch.

    Args:
        project_id: UUID of the Flexo project.
        branch_id:  UUID of the branch.
        element:    Qualified name of the element to render, e.g.
                    'FlashlightModel::FlashlightSpecificationAndDesign'.
                    Pass an empty string to render the top-level element.

    Returns:
        SVG XML as a string.
    """
    params = {
        "apiBase":   FLEXO_API,
        "projectId": project_id,
        "branchId":  branch_id,
        "element":   element,
        "format":    "svg",
    }
    resp = requests.get(f"{SERVER}/render", params=params, timeout=300)
    resp.raise_for_status()
    return resp.text


# ── Example usage ─────────────────────────────────────────────────────────────

project_id = get_project_id("MyFlashlightProject")
branch_id  = get_branch_id(project_id, "main")

svg = get_svg(
    project_id=project_id,
    branch_id=branch_id,
    element="FlashlightModel::FlashlightSpecificationAndDesign",
)

# Write to file
with open("diagram.svg", "w", encoding="utf-8") as f:
    f.write(svg)

# Or embed directly in a Jupyter notebook
from IPython.display import SVG, display
display(SVG(svg))
```

### Notes

- **Render timeout:** The server allows up to 3 minutes per render. Set `timeout=300` on the `GET /render` call.
- **`element` parameter:** Use the fully qualified SysML name (`PackageA::SubPackage::MyBlock`). If omitted or blank the server renders the top-level element of the model.
- **`format` parameter:** Supports `svg` (default), `plantuml`/`puml`, and `text`/`txt`.
- **Known project/branch IDs:** If you already have the UUIDs (e.g. from a previous call or from the Flexo API directly) you can skip the discovery calls and invoke `get_svg` directly.

## Retrieving SysML Text Programmatically (Python)

`GET /textual` loads the same model as `/render` but returns the SysML v2 textual representation of the element instead of a diagram. The discovery helpers (`get_project_id`, `get_branch_id`) from the SVG example above are reused here.

```python
import requests

SERVER = "http://localhost:8088"
FLEXO_API = "https://experimental.starforge.app/"
BEARER_TOKEN = "Bearer eyJ..."


def get_project_id(project_name: str) -> str:
    resp = requests.post(
        f"{SERVER}/projects",
        json={"apiBase": FLEXO_API, "bearerToken": BEARER_TOKEN},
        timeout=30,
    )
    resp.raise_for_status()
    projects = resp.json()["projects"]
    match = next((p for p in projects if p.get("name") == project_name), None)
    if match is None:
        raise ValueError(f"Project '{project_name}' not found")
    return match["id"]


def get_branch_id(project_id: str, branch_name: str) -> str:
    resp = requests.post(
        f"{SERVER}/branches",
        json={"apiBase": FLEXO_API, "bearerToken": BEARER_TOKEN, "projectId": project_id},
        timeout=30,
    )
    resp.raise_for_status()
    branches = resp.json()["branches"]
    match = next((b for b in branches if b.get("name") == branch_name), None)
    if match is None:
        raise ValueError(f"Branch '{branch_name}' not found in project {project_id}")
    return match["id"]


def get_sysml_text(project_id: str, branch_id: str, element: str = "") -> str:
    """
    Fetch the SysML v2 textual representation of an element.

    Args:
        project_id: UUID of the Flexo project.
        branch_id:  UUID of the branch.
        element:    Qualified name of the element, e.g.
                    'FlashlightModel::FlashlightSpecificationAndDesign'.
                    Pass an empty string to retrieve the top-level element.

    Returns:
        SysML v2 text as a string.
    """
    params = {
        "apiBase":   FLEXO_API,
        "projectId": project_id,
        "branchId":  branch_id,
        "element":   element,
    }
    resp = requests.get(f"{SERVER}/textual", params=params, timeout=300)
    resp.raise_for_status()
    return resp.text


# ── Example usage ─────────────────────────────────────────────────────────────

project_id = get_project_id("MyFlashlightProject")
branch_id  = get_branch_id(project_id, "main")

text = get_sysml_text(
    project_id=project_id,
    branch_id=branch_id,
    element="FlashlightModel::FlashlightSpecificationAndDesign",
)

print(text)

# Write to file
with open("model.sysml", "w", encoding="utf-8") as f:
    f.write(text)
```

### Notes

- **`element` parameter:** Same qualified-name convention as `/render`. If omitted, the server resolves the top-level named element in the model.
- **Fallback serialization:** For models loaded from a remote repository, Xtext serialization may not always succeed. In that case the server returns generated SysML-like text that preserves the model structure but may differ from the original source formatting.
- **Timeout:** Allow up to 3 minutes (`timeout=300`) — the load and serialization step can be slow for large models.

## Exporting Parsed SysML as JSON (Python)

`POST /textual/json` parses SysML text using the Pilot runtime and returns a JSON array of DataVersion objects — the same format the server sends to Flexo during a replacement commit. This is useful for inspecting the parsed AST or for offline processing without pushing to a repository.

```python
import requests

SERVER = "http://localhost:8088"

MODEL_TEXT = """
package FlashlightModel {
    part def Flashlight {
        part battery : Battery;
    }
    part def Battery;
}
"""


def export_parsed_json(model_text: str) -> list:
    """Parse SysML text and return it as a list of DataVersion JSON objects."""
    resp = requests.post(
        f"{SERVER}/textual/json",
        json={"modelText": model_text},
        timeout=180,
    )
    resp.raise_for_status()
    return resp.json()


elements = export_parsed_json(MODEL_TEXT)
print(f"Exported {len(elements)} DataVersion objects")

# Write to file
import json
with open("model.json", "w", encoding="utf-8") as f:
    json.dump(elements, f, indent=2)
```

### Notes

- The response body is a JSON array, not a JSON object.
- The endpoint returns HTTP 400 if the model text has parse errors. Check the error message for details.
- This endpoint does not interact with a remote SysML API — it only uses the locally bundled Pilot runtime and standard library.

## Downloading Flexo Elements as JSON (Python)

`POST /elements` fetches all elements for a given project and branch directly from the Flexo API and returns them as a downloadable JSON file. The server resolves the branch's head commit automatically.

```python
import json
import requests

SERVER = "http://localhost:8088"
FLEXO_API = "https://experimental.starforge.app/"
BEARER_TOKEN = "Bearer eyJ..."


def get_project_id(project_name: str) -> str:
    resp = requests.post(
        f"{SERVER}/projects",
        json={"apiBase": FLEXO_API, "bearerToken": BEARER_TOKEN},
        timeout=30,
    )
    resp.raise_for_status()
    projects = resp.json()["projects"]
    match = next((p for p in projects if p.get("name") == project_name), None)
    if match is None:
        raise ValueError(f"Project '{project_name}' not found")
    return match["id"]


def get_branch_id(project_id: str, branch_name: str) -> str:
    resp = requests.post(
        f"{SERVER}/branches",
        json={"apiBase": FLEXO_API, "bearerToken": BEARER_TOKEN, "projectId": project_id},
        timeout=30,
    )
    resp.raise_for_status()
    branches = resp.json()["branches"]
    match = next((b for b in branches if b.get("name") == branch_name), None)
    if match is None:
        raise ValueError(f"Branch '{branch_name}' not found in project {project_id}")
    return match["id"]


def download_elements(project_id: str, branch_id: str) -> list:
    """Fetch all elements for the branch's head commit from Flexo."""
    resp = requests.post(
        f"{SERVER}/elements",
        json={
            "apiBase": FLEXO_API,
            "bearerToken": BEARER_TOKEN,
            "projectId": project_id,
            "branchId": branch_id,
        },
        timeout=300,
    )
    resp.raise_for_status()
    return resp.json()


# ── Example usage ─────────────────────────────────────────────────────────────

project_id = get_project_id("MyFlashlightProject")
branch_id  = get_branch_id(project_id, "main")
elements   = download_elements(project_id, branch_id)

print(f"Downloaded {len(elements)} elements")

with open("elements.json", "w", encoding="utf-8") as f:
    json.dump(elements, f, indent=2)
```

### Notes

- The server resolves the branch's head commit internally; you only need to supply the branch UUID.
- Large models may take tens of seconds. Use `timeout=300` or higher.
- The response is the raw Flexo API payload and may be a JSON array or a JSON object with an `elements` key depending on your Flexo version.

## Configuration

The service listens on `PORT`, default `8088`.

Supported environment variables:

- `PORT`
  HTTP port for the service. Default: `8088`
- `SYSML_API_BASE`
  Backend base URL. In standalone mode it prefills the UI. In embedded mode it is the effective backend base URL used by the server.
- `SYSML_UI_MODE`
  Deployment mode. Supported values: `standalone` or `embedded`. Default: `standalone`
- `SYSML_ALLOW_UI_API_OVERRIDE`
  Whether browser-supplied `apiBase` values may override the environment default. Default: `true` in standalone mode, `false` in embedded mode
- `PYTHON_BIN`
  Python executable used by the bridge scripts. Default in Docker: `python3`
- `SYSML_RENDER_TIMEOUT_MS`
  Timeout for graphical rendering requests. Default: `180000`
- `SYSML_TEXTUAL_TIMEOUT_MS`
  Timeout for textual load/readback requests. Default: `180000`
- `SYSML_COMMIT_TIMEOUT_MS`
  Timeout for the server-side commit workflow. Default: `300000`
- `SYSML_COMMIT_HTTP_TIMEOUT_SEC`
  HTTP timeout used by the Python commit bridge. Default: `300`
- `SYSML_API_TOKEN`
  Server-side bearer token used by the Python-client-backed mutation flows such as project creation and replacement commit. In embedded mode this should be supplied by the environment instead of the UI.
- `SYSML_LIBRARY_PATH`
  Path to the bundled SysML standard library directory. Default in Docker: `/opt/sysml.library`. Override only if mounting an external library.

The Docker image already contains the standard library and uses `/opt/sysml.library` internally. No external mount is required.

## Prerequisites

- Java 21 (Temurin recommended)
- Maven 3.9+ (required only for building pilot JARs from source)
- Python 3.9+
- Docker

## Building the Pilot JARs from Source

The service depends on the [SysML v2 Pilot Implementation](https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation) (LGPL-3.0-or-later). The pinned version is recorded in `.pilot-version`.

**Step 1 — clone the pilot source at the pinned tag:**

```bash
PILOT_REF=$(grep -v '^#' .pilot-version | tr -d '[:space:]')
git clone --depth=1 --branch "$PILOT_REF" \
  https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation.git \
  /tmp/pilot-src
```

**Step 2 — build with Maven (Eclipse Tycho; first run downloads Eclipse p2 deps — allow 30–60 min):**

```bash
cd /tmp/pilot-src
mvn clean package -DskipTests -B
```

**Step 3 — collect the runtime JARs:**

```bash
mkdir -p pilot-jars
find /tmp/pilot-src \
  -name "*.jar" \
  ! -name "*-sources.jar" \
  ! -name "*-javadoc.jar" \
  ! -path "*/test-classes/*" \
  -exec cp -n {} pilot-jars/ \;
```

**Step 4 — sync the standard library (optional but recommended):**

```bash
rm -rf sysml.library
cp -r /tmp/pilot-src/sysml.library sysml.library/
```

After these steps, `pilot-jars/` and `sysml.library/` are ready for the Docker build and local tests.

A convenience script `build-from-pilot.sh` in the repo root runs all four steps, then the tests, and then builds both Docker images.

## Build

### Starforge image (Planetary Utilities branded)

```bash
docker build \
  -t sysml-viz-starforge:latest \
  -f Dockerfile \
  .
```

### Local developer image

Use this when you already have `pilot-jars/` and `sysml.library/` checked out locally and want
to avoid the heavyweight upstream Pilot build during Docker image creation:

```bash
docker build \
  -t sysml-viz-local:latest \
  -f Dockerfile.local \
  .
```

### OpenMBEE community image (unbranded)

The OpenMBEE image is built from the same Java backend but with the OpenMBEE HTML/CSS overrides applied on top of the `app/static/` assets:

```bash
docker build \
  -t sysml-viz-openmbee:latest \
  -f Dockerfile.openmbee \
  .
```

`Dockerfile` and `Dockerfile.openmbee`:

1. read the pinned Pilot ref from `.pilot-version`
2. clone and build the upstream Pilot implementation inside the Docker build
3. compile the Java sources against those generated jars
4. bundle the runtime jars and `sysml.library/`
5. install Graphviz and Python in the runtime image

Local smoke tests still require `pilot-jars/` to be populated ahead of time. The GitHub/CI Docker
image build does not depend on committed `pilot-jars/` or a committed `sysml.library/`.

`Dockerfile.local` is intentionally different:

1. compiles against checked-out `pilot-jars/`
2. bundles the checked-out `sysml.library/`
3. skips the upstream Pilot clone/build stage entirely

## Run

Run locally:

```powershell
docker run --name sysml-viz --rm -p 8088:8088 sysml-viz-service
```

Then open:

- `http://localhost:8088/`
- `http://localhost:8088/editor`

You can also preconfigure the backend base URL:

```powershell
docker run --name sysml-viz --rm -p 8088:8088 `
  -e SYSML_API_BASE=https://experimental.starforge.app/ `
  sysml-viz-service
```

Run in embedded mode:

```powershell
docker run --name sysml-viz --rm -p 8088:8088 `
  -e SYSML_API_BASE=http://sysmlv2-service-svc.<ns>.svc.cluster.local:8080 `
  -e SYSML_API_TOKEN="Bearer ..." `
  -e SYSML_UI_MODE=embedded `
  -e SYSML_ALLOW_UI_API_OVERRIDE=false `
  sysml-viz-service
```

## Browser Usage

### Graphical viewer

The `/` UI lets the user:

- enter the backend base URL in standalone mode
- enter a bearer token in standalone mode
- load projects
- select a project
- set branch and element
- render SVG, PlantUML, or text

### Textual editor

The `/editor` UI lets the user:

- enter the backend base URL in standalone mode
- enter a bearer token in standalone mode
- load projects
- create a project
- select a project
- set branch and root element
- load text
- edit text with lightweight keyword highlighting
- validate
- commit a full replacement model
- export the parsed model as a downloadable JSON file (DataVersion format)
- download all elements for the selected project/branch directly from Flexo as JSON
- open the graphical viewer for the same selection

## Deployment Notes

### Container network model

The browser only needs to reach this service. This service must be able to reach the SysML backend over the container or host network.

Use an internal service hostname for `SYSML_API_BASE`, not a browser-only URL, when running in a composed environment.

### JupyterLab deployment

The usual pattern is:

1. run this service as a sidecar or separate service
2. proxy it through JupyterLab
3. configure `SYSML_API_BASE` to the backend hostname reachable from the service
4. open the proxied viewer/editor URL from JupyterLab

This avoids requiring the browser to talk directly to the SysML backend.

For embedded mode, a typical environment setup is:

```text
PORT=8088
SYSML_API_BASE=http://sysmlv2-service-svc.<ns>.svc.cluster.local:8080
SYSML_API_TOKEN=<from secret>
SYSML_UI_MODE=embedded
SYSML_ALLOW_UI_API_OVERRIDE=false
```

In this mode:

- the API base URL field is hidden
- the bearer token field is hidden
- the server uses environment-provided values instead of asking the user

## Commit Failure Behavior

Large replacement commits can still fail upstream if the remote SysML API times out behind Cloudflare or another proxy.

When that happens, the service now:

- returns a clearer error message for upstream timeout cases
- preserves the generated commit payload file on failure
- logs the preserved payload path for retry and debugging

This does not eliminate upstream `524` or similar timeout responses, but it makes the failure mode easier to understand and retry.

## Current Limits

- `POST /renderJson` is still limited by the supplied Pilot runtime jars.
- Repository-loaded models are not always serializer-safe for Xtext round-tripping, so `/textual` may return fallback SysML-like text for some remote-loaded elements.
- Full replacement commits depend on the performance and timeout behavior of the remote SysML API service.

## Repository Layout

```text
app/
  SysMLVizServer.java
  TextualModelService.java
  sysml_replace_commit.py
  sysml_create_project.py
  static/
    index.html          ← Starforge-branded graphical viewer
    editor.html         ← Starforge-branded textual editor
    starforge-logo.png
    openmbee-logo.svg
    pu-logo.png
openmbee-export/
  app/static/
    index.html          ← OpenMBEE community graphical viewer (unbranded)
    editor.html         ← OpenMBEE community textual editor (unbranded)
    openmbee-logo.svg
pilot-jars/
  *.jar                 ← populated by the pilot build step
sysml.library/
  ...                   ← synced from pilot source tree
tests/
  test_smoke.py
  java/
.pilot-version          ← pinned SysML v2 Pilot Implementation git tag
build-from-pilot.sh     ← end-to-end build script (pilot → tests → Docker)
Dockerfile              ← Starforge image
Dockerfile.openmbee     ← OpenMBEE community image
```

## Testing

Requires `pilot-jars/` to be populated first (see [Building the Pilot JARs from Source](#building-the-pilot-jars-from-source)).

The test runner compiles all Java sources against `pilot-jars/` at startup, starts the server on a free port, and tears it down after the run — no separate server setup needed.

The suite covers:

- route availability and basic request validation for all endpoints
- project and branch listing and creation endpoint validation
- `POST /textual/json` body and modelText validation
- `POST /elements` body and parameter validation
- parsed JSON export: non-empty DataVersion array for valid model, error for invalid SysML
- top-level textual resolution
- resolution tie-breaking
- serializer fallback behavior
- node-model text serialization
- validation JSON shape
- bridge helper failure handling
- feature-chain failure normalization
- branch list name correction for the Flexo API name/UUID mismatch

```bash
python -m unittest discover -s tests -v
```

## Troubleshooting

If `/render` or `/textual` returns `Missing apiBase`:

- set `SYSML_API_BASE`, or provide `apiBase` in the request/UI

If `/render` or `/textual` returns `Provide projectName or projectId`:

- provide a project identifier

If textual validation fails on standard library types:

- make sure you are running the current image, which already includes `sysml.library`

If a large commit fails with an upstream timeout:

- the model likely parsed successfully locally
- the remote backend did not finish the replacement commit in time
- check the server logs for the preserved payload file path

If the UI loads but nothing renders:

- verify the service can reach the backend
- verify the selected project and branch exist
- check `/logs` or the container logs for the detailed server trace
