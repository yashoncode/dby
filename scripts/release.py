"""Creates a GitHub release and uploads the APK and dby.json. Token comes from `git credential fill`.

python scripts/release.py <apk> <versionName> <versionCode> <notes-file>
"""
import hashlib
import json
import os
import subprocess
import sys
import urllib.request

apk, name, code, notes_file = sys.argv[1:]
repo = "yashoncode/dby"
notes = [l.strip() for l in open(notes_file, encoding="utf-8") if l.strip()]
data = open(apk, "rb").read()
sha = hashlib.sha256(data).hexdigest()
asset = f"dby-{name}.apk"
manifest = {
    "package": {"downloadUrl": f"https://github.com/{repo}/releases/download/v{name}/{asset}", "downloadSize": len(data), "sha256": sha},
    name: {"versionCode": int(code), "changelog": notes},
}
cred = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n", capture_output=True, text=True).stdout
token = next(l.split("=", 1)[1] for l in cred.splitlines() if l.startswith("password="))


def call(url, body, ctype="application/json", method="POST"):
    req = urllib.request.Request(url, data=body, method=method, headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "Content-Type": ctype})
    with urllib.request.urlopen(req) as r:
        return json.load(r)


body = "\n".join(f"- {n}" for n in notes) + f"\n\n**Install:** download `{asset}`, allow installs from your browser, open it. Android 8.0+.\n\nSHA-256: `{sha}`"
release = call(f"https://api.github.com/repos/{repo}/releases", json.dumps({
    "tag_name": f"v{name}", "target_commitish": "main", "name": f"DBY {name}", "body": body,
    "draft": True, "prerelease": False}).encode())
up = f"https://uploads.github.com/repos/{repo}/releases/{release['id']}/assets?name="
print(call(up + asset, data, "application/vnd.android.package-archive")["browser_download_url"])
print(call(up + "dby.json", json.dumps(manifest, indent=2).encode())["browser_download_url"])
# Published only once both files are up, so "latest" never points at a release without dby.json.
release = call(f"https://api.github.com/repos/{repo}/releases/{release['id']}", json.dumps({"draft": False, "make_latest": "true"}).encode(), method="PATCH")
print(release["html_url"], sha)
