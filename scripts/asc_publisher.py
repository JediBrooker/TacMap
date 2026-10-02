#!/usr/bin/env python3
"""App Store Connect API helper, stdlib + openssl only (no pyjwt here).

Key: ~/.appstoreconnect/private_keys/AuthKey_<ASC_KEY_ID>.p8, issuer in ASC_ISSUER.
Import it and use ASC().get/post/patch/delete, or the upload_asset helper for
screenshots + previews (reserve -> PUT parts -> commit w/ md5).
"""
import base64, hashlib, json, os, subprocess, sys, tempfile, time, urllib.error, urllib.request

# ids come from env so nothing account-specific lands in git
KEY_ID = os.environ["ASC_KEY_ID"]
ISSUER = os.environ["ASC_ISSUER"]
KEY = os.path.expanduser(f"~/.appstoreconnect/private_keys/AuthKey_{KEY_ID}.p8")
BASE = "https://api.appstoreconnect.apple.com"


def b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def der_to_raw(der):
    # openssl spits out a DER ECDSA sig, JWT wants raw r||s (32 bytes each)
    assert der[0] == 0x30
    i = 2
    out = b""
    for _ in range(2):
        assert der[i] == 0x02
        n = der[i + 1]
        v = der[i + 2:i + 2 + n].lstrip(b"\x00")
        out += v.rjust(32, b"\x00")
        i += 2 + n
    return out


def make_token():
    now = int(time.time())
    head = b64(json.dumps({"alg": "ES256", "kid": KEY_ID, "typ": "JWT"}).encode())
    body = b64(json.dumps({"iss": ISSUER, "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"}).encode())
    msg = f"{head}.{body}".encode()
    der = subprocess.run(["openssl", "dgst", "-sha256", "-sign", KEY], input=msg, capture_output=True, check=True).stdout
    return f"{head}.{body}.{b64(der_to_raw(der))}"


class ASC:
    def __init__(self):
        self.tok, self.born = make_token(), time.time()

    def req(self, method, path, body=None):
        if time.time() - self.born > 900:
            self.tok, self.born = make_token(), time.time()
        url = path if path.startswith("http") else BASE + path
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(url, data=data, method=method, headers={
            "Authorization": "Bearer " + self.tok, "Content-Type": "application/json"})
        try:
            raw = urllib.request.urlopen(r, timeout=300).read()
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"{method} {path} -> {e.code}\n{e.read().decode()}")
        return json.loads(raw) if raw else {}

    get = lambda s, p: s.req("GET", p)
    post = lambda s, p, b: s.req("POST", p, b)
    patch = lambda s, p, b: s.req("PATCH", p, b)
    delete = lambda s, p: s.req("DELETE", p)

    def upload_asset(self, kind, rel_name, rel_type, rel_id, path, extra=None):
        """kind = appScreenshots | appPreviews"""
        blob = open(path, "rb").read()
        attrs = {"fileName": os.path.basename(path), "fileSize": len(blob)}
        attrs.update(extra or {})
        res = self.post(f"/v1/{kind}", {"data": {"type": kind, "attributes": attrs,
            "relationships": {rel_name: {"data": {"type": rel_type, "id": rel_id}}}}})["data"]
        for op in res["attributes"]["uploadOperations"]:
            chunk = blob[op["offset"]:op["offset"] + op["length"]]
            hdrs = {h["name"]: h["value"] for h in op.get("requestHeaders", [])}
            urllib.request.urlopen(urllib.request.Request(op["url"], data=chunk, method=op["method"], headers=hdrs), timeout=600).read()
        self.patch(f"/v1/{kind}/{res['id']}", {"data": {"type": kind, "id": res["id"],
            "attributes": {"uploaded": True, "sourceFileChecksum": hashlib.md5(blob).hexdigest()}}})
        return res["id"]
