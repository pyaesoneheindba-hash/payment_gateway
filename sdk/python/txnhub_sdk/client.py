from __future__ import annotations
import base64, hashlib, json, time, uuid
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

class TxnHubError(RuntimeError):
    def __init__(self, status_code: int, response_body: str):
        super().__init__(f"TXN HUB request failed with HTTP {status_code}: {response_body}")
        self.status_code = status_code
        self.response_body = response_body

def content_digest(body: bytes) -> str:
    return "sha-256=:" + base64.b64encode(hashlib.sha256(body).digest()).decode("ascii") + ":"

def canonicalize(method: str, raw_path: str, raw_query: str, merchant_id: str, key_id: str,
                 timestamp: str, nonce: str, idempotency_key: str, digest: str) -> str:
    return "\n".join(["PGSIG1", method.upper(), raw_path, raw_query or "", merchant_id, key_id,
                      timestamp, nonce, idempotency_key or "", digest])

class TxnHubClient:
    def __init__(self, base_url: str, merchant_id: str, key_id: str, private_key_pem: bytes):
        self.base_url = base_url.rstrip("/")
        self.merchant_id = str(uuid.UUID(merchant_id))
        self.key_id = key_id
        key = serialization.load_pem_private_key(private_key_pem, password=None)
        if not isinstance(key, Ed25519PrivateKey):
            raise TypeError("TXN HUB requires an Ed25519 PKCS#8 private key")
        self.private_key = key

    @classmethod
    def from_private_key_file(cls, base_url: str, merchant_id: str, key_id: str, private_key_path: str | Path):
        return cls(base_url, merchant_id, key_id, Path(private_key_path).read_bytes())

    @staticmethod
    def new_idempotency_key() -> str:
        return str(uuid.uuid4())

    def create_payment(self, body: dict, idempotency_key: str | None = None):
        return self.post_json("/api/v1/payments", body, idempotency_key or self.new_idempotency_key())

    def get_payment(self, payment_id: str):
        return self.request("GET", f"/api/v1/payments/{payment_id}")

    def reconcile_payment(self, payment_id: str):
        return self.request("POST", f"/api/v1/payments/{payment_id}/reconcile")

    def refund_payment(self, payment_id: str, body: dict, idempotency_key: str | None = None):
        return self.post_json(f"/api/v1/payments/{payment_id}/refunds", body,
                              idempotency_key or self.new_idempotency_key())

    def void_payment(self, payment_id: str, provider_options: dict | None = None, idempotency_key: str | None = None):
        return self.post_json(f"/api/v1/payments/{payment_id}/void",
                              {"providerOptions": provider_options or {}},
                              idempotency_key or self.new_idempotency_key())

    def post_json(self, path: str, body: dict, idempotency_key: str):
        raw = json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        return self.request("POST", path, body=raw, idempotency_key=idempotency_key,
                            content_type="application/json")

    def request(self, method: str, path: str, raw_query: str = "", body: bytes = b"",
                idempotency_key: str = "", content_type: str | None = None):
        timestamp = str(int(time.time()))
        nonce = str(uuid.uuid4())
        digest = content_digest(body)
        canonical = canonicalize(method, path, raw_query, self.merchant_id, self.key_id,
                                 timestamp, nonce, idempotency_key, digest)
        signature = base64.b64encode(self.private_key.sign(canonical.encode("utf-8"))).decode("ascii")
        headers = {
            "X-Merchant-Id": self.merchant_id,
            "X-Key-Id": self.key_id,
            "X-Timestamp": timestamp,
            "X-Nonce": nonce,
            "Content-Digest": digest,
            "X-Signature": signature,
        }
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        if content_type:
            headers["Content-Type"] = content_type
        url = self.base_url + path + (("?" + raw_query) if raw_query else "")
        req = Request(url, data=body if body else None, headers=headers, method=method.upper())
        try:
            with urlopen(req, timeout=30) as response:
                text = response.read().decode("utf-8")
                content_type_header = response.headers.get("content-type", "")
                data = json.loads(text) if "application/json" in content_type_header and text else text
                return {"status": response.status, "data": data, "headers": dict(response.headers.items())}
        except HTTPError as exc:
            body_text = exc.read().decode("utf-8", errors="replace")
            raise TxnHubError(exc.code, body_text) from exc

def verify_webhook(gateway_public_key_pem: bytes, event_id: str, timestamp: str,
                   content_digest_header: str, signature_base64: str, body: bytes,
                   allowed_clock_skew_seconds: int = 300) -> bool:
    try:
        if abs(int(time.time()) - int(timestamp)) > allowed_clock_skew_seconds:
            return False
        if not hashlib.compare_digest(content_digest(body), content_digest_header):
            return False
        key = serialization.load_pem_public_key(gateway_public_key_pem)
        if not isinstance(key, Ed25519PublicKey):
            return False
        canonical = "\n".join(["PGWH1", event_id, timestamp, content_digest_header])
        key.verify(base64.b64decode(signature_base64), canonical.encode("utf-8"))
        return True
    except Exception:
        return False
