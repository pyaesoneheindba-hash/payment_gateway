import json, base64
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from txnhub_sdk import canonicalize, content_digest

base = Path(__file__).parent / "test-vectors"
v = json.loads((base / "vector.json").read_text())
body = v["bodyUtf8"].encode()
assert content_digest(body) == v["contentDigest"]
canonical = canonicalize(v["method"], v["rawPath"], v["rawQuery"], v["merchantId"], v["keyId"], v["timestamp"], v["nonce"], v["idempotencyKey"], v["contentDigest"])
assert canonical == v["canonical"]
key = serialization.load_pem_private_key((base / "test-private-key.pem").read_bytes(), password=None)
assert base64.b64encode(key.sign(canonical.encode())).decode() == v["signatureBase64"]
print("TXN HUB Python SDK conformance vector PASS")
