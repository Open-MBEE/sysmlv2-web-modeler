"""Read-only Starforge qualification for a running candidate container."""

import json
import os
import sys
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def request(path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = Request(
        "http://127.0.0.1:8088" + path,
        data=data,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urlopen(req, timeout=120) as response:
            return response.read().decode()
    except HTTPError as error:
        detail = error.read(2048).decode(errors="replace")
        raise RuntimeError(f"{req.get_method()} {path} returned {error.code}: {detail}") from error


def main():
    config = json.loads(request("/config"))
    assert config["uiMode"] == sys.argv[1]
    assert "apiToken" not in config
    assert request("/") and request("/editor")

    project = os.environ["SYSML_TEST_PROJECT_ID"]
    branch = os.environ["SYSML_TEST_BRANCH_ID"]
    projects = json.loads(request("/projects", {}))["projects"]
    assert any(item["id"] == project for item in projects)
    branches = json.loads(request("/branches", {"projectId": project}))["branches"]
    assert any(item["id"] == branch for item in branches)
    selection = {"projectId": project, "branchId": branch}
    assert isinstance(json.loads(request("/elements", selection)), list)
    for fmt, marker in (("svg", "<svg"), ("plantuml", "@startuml")):
        assert marker in request("/render?" + urlencode({**selection, "format": fmt}))
    assert request("/render?" + urlencode({**selection, "format": "text"})).strip()

    draft = {"modelText": "package Demo { part def Vehicle { part wheel; } }"}
    assert not json.loads(request("/textual/validate", draft))["hasErrors"]
    assert json.loads(request("/textual/json", draft))
    print(f"{sys.argv[1]} qualification passed")


if __name__ == "__main__":
    main()
