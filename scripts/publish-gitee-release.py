#!/usr/bin/env python3
"""Stage a signed Gitee release; promote only after anonymous metadata/download verification.

Credentials are read only from GITEE_TOKEN and sent only to the Gitee API.
An interrupted run can be resumed without replacing an existing attachment.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlsplit

import requests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default="a962327460/eta")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--previous-apk", type=Path, required=True)
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9-]+/[A-Za-z0-9_.-]+", args.repository):
        raise RuntimeError("Invalid repository")
    if not re.fullmatch(r"v[0-9]{1,9}(\.[0-9]{1,9}){2}", args.tag):
        raise RuntimeError("Invalid stable tag")
    name = f"Eta-{args.tag}.apk"
    if args.apk.name != name or not 0 < args.apk.stat().st_size <= 128 * 1024 * 1024:
        raise RuntimeError("Unexpected APK name or size")

    def identity(path):
        signature = subprocess.check_output([args.apksigner, "verify", "--verbose", "--print-certs", str(path)], text=True)
        signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})", signature)
        if not signers:
            raise RuntimeError("APK has no verified signer")
        badging = subprocess.check_output([args.aapt, "dump", "badging", str(path)], text=True, encoding="utf-8")
        package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
        if not package:
            raise RuntimeError("Unreadable APK identity")
        return package[1], int(package[2]), package[3], set(s.lower() for s in signers)

    previous, candidate = identity(args.previous_apk), identity(args.apk)
    if candidate[0] != "io.github.mangi.eta" or candidate[0] != previous[0] or candidate[1] <= previous[1] or candidate[2] != args.tag[1:] or candidate[3] != previous[3]:
        raise RuntimeError("APK package/version/signer gate failed")
    digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    manifest = {"schema": 1, "name": name, "size": args.apk.stat().st_size, "sha256": digest}
    notes = args.notes.read_text(encoding="utf-8-sig")
    if "```eta-update" in notes:
        raise RuntimeError("Notes must not already contain an update manifest")
    body = notes.rstrip() + "\n\n```eta-update\n" + json.dumps(manifest, separators=(",", ":")) + "\n```\n"
    base = f"https://gitee.com/api/v5/repos/{args.repository}"
    session = requests.Session()
    session.headers.update({"Authorization": "Bearer " + os.environ["GITEE_TOKEN"], "User-Agent": "Eta-release-publisher"})

    def call(method, route, expected=200, **kwargs):
        response = session.request(method, "https://gitee.com/api/v5/" + route, timeout=(20, 300), allow_redirects=False, **kwargs)
        if response.status_code != expected:
            raise RuntimeError(f"Gitee {method} {route}: HTTP {response.status_code}")
        return response.json()

    identity_json = call("GET", "user")
    if identity_json.get("login", "").lower() != args.repository.split("/")[0].lower():
        raise RuntimeError("Wrong repository owner")
    repository = call("GET", "repos/" + args.repository)
    if repository.get("private") is not False:
        raise RuntimeError("Repository must be public")
    commit = subprocess.check_output(["git", "rev-parse", args.tag + "^{commit}"], text=True).strip()
    tags = call("GET", f"repos/{args.repository}/tags?per_page=100")
    tag = next((t for t in tags if t.get("name") == args.tag), None)
    if not tag or tag.get("commit", {}).get("sha") != commit:
        raise RuntimeError("Remote tag differs from the validated local tag")
    route = f"repos/{args.repository}/releases"
    response = session.get(base + "/releases/tags/" + args.tag, timeout=30, allow_redirects=False)
    if response.status_code == 404:
        release = call("POST", route, 201, data={"tag_name": args.tag, "name": f"Eta {args.tag}", "body": body, "prerelease": "true", "target_commitish": commit})
    elif response.status_code == 200:
        release = response.json()
        if release.get("body") != body:
            raise RuntimeError("Existing release notes/manifest differ; no overwrite")
    else:
        raise RuntimeError(f"Gitee release lookup: HTTP {response.status_code}")
    release_id = int(release["id"])
    print(f"STAGED_RELEASE={release_id}", flush=True)
    attachments_route = f"{route}/{release_id}/attach_files"
    attachments = call("GET", attachments_route + "?per_page=100")
    matching = [a for a in attachments if a.get("name") == name]
    if not matching:
        with args.apk.open("rb") as stream:
            call("POST", attachments_route, 201, files={"file": (name, stream, "application/vnd.android.package-archive")})
    elif len(matching) != 1 or matching[0].get("size") != manifest["size"]:
        raise RuntimeError("Conflicting attachment; no overwrite")

    # Deliberately use a separate anonymous session: no credentials on downloads or CDN redirects.
    public = requests.Session()
    public.headers.update({"User-Agent": "Eta-Update-Checker", "Accept": "application/json", "Accept-Encoding": "identity"})
    def anonymous_json(url):
        response = public.get(url, timeout=30, allow_redirects=False)
        if response.status_code != 200:
            raise RuntimeError(f"Anonymous Gitee verification: HTTP {response.status_code}; release not promoted")
        return response.json()
    visible = anonymous_json(base + "/releases/tags/" + args.tag)
    if visible.get("body") != body or visible.get("id") != release_id:
        raise RuntimeError("Anonymous release metadata differs")
    items = anonymous_json(base + f"/releases/{release_id}/attach_files?per_page=100")
    matching = [a for a in items if a.get("name") == name]
    url = f"https://gitee.com/{args.repository}/releases/download/{args.tag}/{name}"
    if len(items) >= 100 or len(matching) != 1 or matching[0].get("size") != manifest["size"] or matching[0].get("browser_download_url") != url:
        raise RuntimeError("Anonymous attachment metadata differs")
    attachment_id = int(matching[0]["id"])
    attachment_path = f"/{args.repository}/attach_files/{attachment_id}/download/{name}"
    for _ in range(4):
        response = public.get(url, stream=True, timeout=(20, 180), allow_redirects=False)
        if response.status_code in (301, 302, 303, 307, 308):
            next_url = requests.compat.urljoin(url, response.headers.get("Location", ""))
            response.close()
            parsed = urlsplit(next_url)
            cdn = re.fullmatch(r"/attach_file/[0-9]+/" + re.escape(name), parsed.path)
            if parsed.scheme != "https" or parsed.port not in (None, 443) or parsed.username or parsed.password or parsed.fragment or not (
                (parsed.hostname == "gitee.com" and parsed.path == attachment_path and not parsed.query) or
                (parsed.hostname == "foruda.gitee.com" and cdn)
            ):
                raise RuntimeError("Untrusted attachment redirect")
            url = next_url
            continue
        if response.status_code != 200:
            response.close()
            raise RuntimeError("Anonymous APK download failed; release not promoted")
        actual = hashlib.sha256(); size = 0
        with response:
            for chunk in response.iter_content(64 * 1024):
                size += len(chunk)
                if size > manifest["size"]:
                    raise RuntimeError("Downloaded APK exceeds declared size")
                actual.update(chunk)
        if size != manifest["size"] or actual.hexdigest() != digest:
            raise RuntimeError("Downloaded APK size or SHA-256 differs")
        break
    else:
        raise RuntimeError("Too many download redirects")
    published = call("PATCH", f"{route}/{release_id}", data={"name": f"Eta {args.tag}", "tag_name": args.tag, "body": body, "prerelease": "false"})
    if published.get("prerelease") is not False:
        raise RuntimeError("Gitee did not confirm stable publication")
    latest = anonymous_json(base + "/releases/latest")
    if latest.get("tag_name") != args.tag or latest.get("body") != body:
        raise RuntimeError("Stable published, but anonymous latest metadata not confirmed")
    print(f"PUBLISHED=https://gitee.com/{args.repository}/releases/tag/{args.tag}", flush=True)
    print(f"APK_SHA256={digest}", flush=True)


if __name__ == "__main__":
    main()
