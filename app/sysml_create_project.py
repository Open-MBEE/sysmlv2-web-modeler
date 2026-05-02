#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def log(message):
    print(message, file=sys.stderr, flush=True)


def request_json(method, url, token, payload=None, expected=(200, 201)):
    data = None
    headers = {
        "Accept": "application/json",
        "Authorization": token,
        "User-Agent": "sysmlv2viz-project-create/1.0",
    }
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, method=method, data=data, headers=headers)
    try:
        log(f"[py-project] {method} {url}")
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read().decode("utf-8")
            if response.status not in expected:
                raise RuntimeError(f"Unexpected status {response.status}: {body}")
            log(f"[py-project] status={response.status}")
            return {} if not body else json.loads(body)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} failed with {exc.code}: {body}") from exc


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-base", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--description", default="")
    args = parser.parse_args()

    token = os.environ.get("SYSML_API_TOKEN", "").strip()
    if not token:
        raise RuntimeError("SYSML_API_TOKEN is not configured")

    base_url = args.api_base.rstrip("/")
    payload = {
        "@type": "Project",
        "name": args.name,
        "description": args.description,
    }

    log("[py-project] starting project create bridge")
    log(f"[py-project] api_base={base_url}")
    log(f"[py-project] name={args.name}")
    log(f"[py-project] description_length={len(args.description)}")
    result = request_json("POST", f"{base_url}/projects", token, payload=payload)
    log("[py-project] project create completed")
    json.dump(result, sys.stdout)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        sys.stderr.write(str(exc))
        sys.exit(1)
