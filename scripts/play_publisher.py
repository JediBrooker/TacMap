#!/usr/bin/env python3
"""Tiny Google Play Developer API client, stdlib only (signs the JWT with openssl
like we do for ASC, so no google-auth install needed).

Auth, either:
  PLAY_SA_EMAIL=play-publisher@<project>.iam.gserviceaccount.com  (keyless, gcloud impersonation)
  or a service account JSON at $PLAY_SA_KEY, default ~/.play/tacmap-sa.json (never in repo).

  play_publisher.py check
  play_publisher.py upload app-release.aab --track internal [--notes-dir DIR] [--status draft|completed]
  play_publisher.py listing --lang en-AU [--title T] [--short S] [--full-file F]
  play_publisher.py images --lang en-AU --type phoneScreenshots a.png b.png ... [--replace]
  play_publisher.py tracks

Everything runs inside one edit and only commits at the end, so a failure leaves
nothing half-published. --dry-run validates the edit then throws it away.
"""
import argparse, base64, json, os, subprocess, sys, tempfile, time, urllib.parse, urllib.request

PACKAGE = "com.tacmap"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + PACKAGE
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/" + PACKAGE
KEY_PATH = os.path.expanduser(os.environ.get("PLAY_SA_KEY", "~/.play/tacmap-sa.json"))


def b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


SA_EMAIL = os.environ.get("PLAY_SA_EMAIL")


def token():
    # keyless path: org policy blocks SA key creation, so impersonate via gcloud instead
    if SA_EMAIL:
        return subprocess.run(
            ["gcloud", "auth", "print-access-token", "--impersonate-service-account=" + SA_EMAIL,
             "--scopes=https://www.googleapis.com/auth/androidpublisher"],
            capture_output=True, text=True, check=True).stdout.strip()
    sa = json.load(open(KEY_PATH))
    now = int(time.time())
    head = b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64(json.dumps({
        "iss": sa["client_email"], "aud": sa["token_uri"], "iat": now, "exp": now + 3600,
        "scope": "https://www.googleapis.com/auth/androidpublisher",
    }).encode())
    signing = f"{head}.{claims}".encode()
    with tempfile.NamedTemporaryFile("w", delete=False) as kf:
        kf.write(sa["private_key"])
    try:
        os.chmod(kf.name, 0o600)
        sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", kf.name],
                             input=signing, capture_output=True, check=True).stdout
    finally:
        os.unlink(kf.name)
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": f"{head}.{claims}.{b64(sig)}",
    }).encode()
    return json.load(urllib.request.urlopen(sa["token_uri"], body))["access_token"]


class Play:
    def __init__(self):
        self.tok = token()

    def call(self, method, url, body=None, data=None, ctype=None):
        headers = {"Authorization": "Bearer " + self.tok}
        if body is not None:
            data, ctype = json.dumps(body).encode(), "application/json"
        if ctype:
            headers["Content-Type"] = ctype
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            raw = urllib.request.urlopen(req, timeout=600).read()
        except urllib.error.HTTPError as e:
            sys.exit(f"{method} {url} -> {e.code}\n{e.read().decode()}")
        return json.loads(raw) if raw else {}

    def edit(self):
        return self.call("POST", API + "/edits", {})["id"]

    def finish(self, eid, dry):
        if dry:
            self.call("POST", f"{API}/edits/{eid}:validate")
            self.call("DELETE", f"{API}/edits/{eid}")
            print("dry run ok, edit validated + discarded")
        else:
            self.call("POST", f"{API}/edits/{eid}:commit")
            print("committed")


def read_notes(d):
    # one file per locale, eg notes/en-AU.txt. play caps these at 500 chars
    out = []
    for f in sorted(os.listdir(d)):
        if f.endswith(".txt"):
            text = open(os.path.join(d, f)).read().strip()
            if len(text) > 500:
                sys.exit(f"{f} is {len(text)} chars, play max is 500")
            out.append({"language": f[:-4], "text": text})
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--dry-run", action="store_true")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("check")
    sub.add_parser("tracks")
    u = sub.add_parser("upload")
    u.add_argument("aab")
    u.add_argument("--track", default="internal")
    u.add_argument("--status", default="draft", choices=["draft", "completed", "inProgress"])
    u.add_argument("--notes-dir")
    l = sub.add_parser("listing")
    l.add_argument("--lang", required=True)
    l.add_argument("--title")
    l.add_argument("--short")
    l.add_argument("--full-file")
    i = sub.add_parser("images")
    i.add_argument("--lang", required=True)
    i.add_argument("--type", required=True, help="phoneScreenshots, sevenInchScreenshots, tenInchScreenshots, featureGraphic, icon")
    i.add_argument("--replace", action="store_true", help="delete existing images of this type first")
    i.add_argument("files", nargs="+")
    a = p.parse_args()

    play = Play()
    eid = play.edit()
    e = f"{API}/edits/{eid}"

    if a.cmd in ("check", "tracks"):
        for t in play.call("GET", e + "/tracks").get("tracks", []):
            for r in t.get("releases", []):
                print(f"{t['track']:12} {r.get('status'):11} {r.get('name','')} codes={r.get('versionCodes')}")
        if a.cmd == "check":
            print("auth + permissions ok")
        play.call("DELETE", e)
        return

    if a.cmd == "upload":
        print(f"uploading {a.aab} ({os.path.getsize(a.aab) >> 20} MB)...")
        res = play.call("POST", f"{UPLOAD}/edits/{eid}/bundles?uploadType=media",
                        data=open(a.aab, "rb").read(), ctype="application/octet-stream")
        code = res["versionCode"]
        print(f"bundle versionCode {code}")
        rel = {"versionCodes": [str(code)], "status": a.status}
        if a.notes_dir:
            rel["releaseNotes"] = read_notes(a.notes_dir)
        play.call("PUT", f"{e}/tracks/{a.track}", {"track": a.track, "releases": [rel]})
        print(f"assigned to {a.track} as {a.status}")

    elif a.cmd == "listing":
        cur = play.call("GET", f"{e}/listings/{a.lang}")
        if a.title: cur["title"] = a.title
        if a.short: cur["shortDescription"] = a.short
        if a.full_file: cur["fullDescription"] = open(a.full_file).read().strip()
        cur["language"] = a.lang
        play.call("PUT", f"{e}/listings/{a.lang}", cur)
        print(f"listing {a.lang} updated")

    elif a.cmd == "images":
        base = f"{e}/listings/{a.lang}/{a.type}"
        if a.replace:
            play.call("DELETE", base)
        for f in a.files:
            ctype = "image/png" if f.lower().endswith(".png") else "image/jpeg"
            play.call("POST", f"{UPLOAD}/edits/{eid}/listings/{a.lang}/{a.type}?uploadType=media",
                      data=open(f, "rb").read(), ctype=ctype)
            print("uploaded", f)

    play.finish(eid, a.dry_run)


if __name__ == "__main__":
    main()
