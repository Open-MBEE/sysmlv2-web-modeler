#!/usr/bin/env python3
import argparse
import json
import os
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request

HTTP_TIMEOUT_SEC = int(os.environ.get("SYSML_COMMIT_HTTP_TIMEOUT_SEC", "300"))


def log(message):
    print(message, file=sys.stderr, flush=True)


def is_cloudflare_524(body):
    if not body:
        return False
    normalized = body.lower()
    return (
        "error 524" in normalized
        or "cloudflare ray id" in normalized
        or "the origin web server timed out responding to this request" in normalized
    )


def request_json(method, url, token, payload=None, expected=(200,)):
    data = None
    headers = {
        "Accept": "application/json",
        "Authorization": token,
        "User-Agent": "sysmlv2viz-textual-commit/1.0",
    }
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, method=method, data=data, headers=headers)
    try:
        log(f"[py-commit] {method} {url}")
        with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT_SEC) as response:
            body = response.read().decode("utf-8")
            if response.status not in expected:
                raise RuntimeError(f"Unexpected status {response.status}: {body}")
            log(f"[py-commit] status={response.status}")
            return {} if not body else json.loads(body)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        if method == "POST" and is_cloudflare_524(body):
            raise RuntimeError(
                f"Upstream commit timed out at {urllib.parse.urlparse(url).netloc} "
                f"(Cloudflare 524). The model was parsed locally, but the remote server "
                f"did not finish the replacement commit in time."
            ) from exc
        raise RuntimeError(f"{method} {url} failed with {exc.code}: {body}") from exc
    except (TimeoutError, socket.timeout) as exc:
        if method == "POST":
            raise RuntimeError(
                f"Upstream commit timed out at {urllib.parse.urlparse(url).netloc} "
                f"after {HTTP_TIMEOUT_SEC} seconds while waiting for the replacement commit response."
            ) from exc
        raise RuntimeError(f"{method} {url} timed out after {HTTP_TIMEOUT_SEC} seconds") from exc


def response_list(data):
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        for key in ("elements", "items", "branches", "projects"):
            value = data.get(key)
            if isinstance(value, list):
                return value
        return []
    return []


def branch_id_of(branch):
    if not isinstance(branch, dict):
        return ""
    return (branch.get("@id") or branch.get("id") or "").strip()


def branch_name_of(branch):
    if not isinstance(branch, dict):
        return ""
    return (branch.get("name") or "").strip()


def fetch_branch(base_url, token, project_id, branch_id):
    return request_json("GET", f"{base_url}/projects/{project_id}/branches/{branch_id}", token)


def find_project(base_url, token, project_name=None, project_id=None):
    if project_id:
        return request_json("GET", f"{base_url}/projects/{project_id}", token)
    projects = response_list(request_json("GET", f"{base_url}/projects", token))
    for project in projects:
        if project.get("name") == project_name:
            return project
    raise RuntimeError(f"Project not found: {project_name}")


def find_branch(base_url, token, project_id, branch_name=None, branch_id=None, default_branch=None):
    if branch_id:
        return fetch_branch(base_url, token, project_id, branch_id)
    if branch_name:
        branches = response_list(request_json("GET", f"{base_url}/projects/{project_id}/branches", token))
        for branch in branches:
            if branch_name_of(branch) == branch_name:
                return branch
        for branch in branches:
            current_branch_id = branch_id_of(branch)
            if not current_branch_id:
                continue
            detail = fetch_branch(base_url, token, project_id, current_branch_id)
            if branch_name_of(detail) == branch_name:
                return detail
        raise RuntimeError(f"Branch not found: {branch_name}")
    if isinstance(default_branch, dict) and default_branch.get("@id"):
        return fetch_branch(base_url, token, project_id, default_branch["@id"])
    raise RuntimeError("Could not resolve target branch")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-base", required=True)
    parser.add_argument("--project-name")
    parser.add_argument("--project-id")
    parser.add_argument("--branch-name")
    parser.add_argument("--branch-id")
    parser.add_argument("--payload-file", required=True)
    args = parser.parse_args()

    token = os.environ.get("SYSML_API_TOKEN", "").strip()
    if not token:
        raise RuntimeError("SYSML_API_TOKEN is not configured")
    log("[py-commit] starting commit bridge")
    log(f"[py-commit] api_base={args.api_base.rstrip('/')}")
    log(f"[py-commit] project_name={args.project_name or ''}")
    log(f"[py-commit] project_id={args.project_id or ''}")
    log(f"[py-commit] branch_name={args.branch_name or ''}")
    log(f"[py-commit] branch_id={args.branch_id or ''}")
    log(f"[py-commit] http_timeout_sec={HTTP_TIMEOUT_SEC}")

    with open(args.payload_file, "r", encoding="utf-8") as fh:
        commit_payload = json.load(fh)
    log(f"[py-commit] payload_file={args.payload_file}")
    log(f"[py-commit] change_count={len(commit_payload.get('change', []))}")

    base_url = args.api_base.rstrip("/")
    project = find_project(base_url, token, args.project_name, args.project_id)
    project_id = project.get("@id") or project.get("id")
    if not project_id:
        raise RuntimeError("Resolved project has no id")
    log(f"[py-commit] resolved project_id={project_id}")

    branch = find_branch(
        base_url,
        token,
        project_id,
        args.branch_name,
        args.branch_id,
        project.get("defaultBranch"),
    )
    branch_id = branch.get("@id") or branch.get("id")
    if not branch_id:
        raise RuntimeError("Resolved branch has no id")
    log(f"[py-commit] resolved branch_id={branch_id}")

    endpoint = f"{base_url}/projects/{project_id}/commits?replace=true&branchId={urllib.parse.quote(branch_id, safe='')}"
    result = request_json("POST", endpoint, token, payload=commit_payload)
    log("[py-commit] commit request completed")
    json.dump(result, sys.stdout)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        sys.stderr.write(str(exc))
        sys.exit(1)
