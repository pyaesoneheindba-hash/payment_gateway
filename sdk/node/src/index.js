import { createHash, createPrivateKey, createPublicKey, randomUUID, sign as edSign, verify as edVerify, timingSafeEqual } from 'node:crypto';
import { readFile } from 'node:fs/promises';

export class TxnHubError extends Error {
  constructor(status, body) {
    super(`TXN HUB request failed with HTTP ${status}: ${body}`);
    this.status = status;
    this.body = body;
  }
}

export class TxnHubClient {
  constructor({ baseUrl, merchantId, keyId, privateKeyPem }) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
    this.merchantId = merchantId;
    this.keyId = keyId;
    this.privateKey = createPrivateKey(privateKeyPem);
  }

  static async fromPrivateKeyFile({ baseUrl, merchantId, keyId, privateKeyPath }) {
    const privateKeyPem = await readFile(privateKeyPath, 'utf8');
    return new TxnHubClient({ baseUrl, merchantId, keyId, privateKeyPem });
  }

  static newIdempotencyKey() { return randomUUID(); }

  async createPayment(body, idempotencyKey = TxnHubClient.newIdempotencyKey()) {
    return this.postJson('/api/v1/payments', body, idempotencyKey);
  }

  async getPayment(paymentId) {
    return this.request({ method: 'GET', path: `/api/v1/payments/${paymentId}` });
  }

  async reconcilePayment(paymentId) {
    return this.request({ method: 'POST', path: `/api/v1/payments/${paymentId}/reconcile` });
  }

  async refundPayment(paymentId, body, idempotencyKey = TxnHubClient.newIdempotencyKey()) {
    return this.postJson(`/api/v1/payments/${paymentId}/refunds`, body, idempotencyKey);
  }

  async voidPayment(paymentId, providerOptions = {}, idempotencyKey = TxnHubClient.newIdempotencyKey()) {
    return this.postJson(`/api/v1/payments/${paymentId}/void`, { providerOptions }, idempotencyKey);
  }

  async postJson(path, body, idempotencyKey) {
    const bytes = Buffer.from(JSON.stringify(body), 'utf8');
    return this.request({ method: 'POST', path, bodyBytes: bytes, idempotencyKey, contentType: 'application/json' });
  }

  async request({ method, path, rawQuery = '', bodyBytes = Buffer.alloc(0), idempotencyKey = '', contentType }) {
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const nonce = randomUUID();
    const digest = contentDigest(bodyBytes);
    const canonical = canonicalize(method, path, rawQuery, this.merchantId, this.keyId, timestamp, nonce, idempotencyKey, digest);
    const signature = edSign(null, Buffer.from(canonical, 'utf8'), this.privateKey).toString('base64');
    const headers = {
      'X-Merchant-Id': this.merchantId,
      'X-Key-Id': this.keyId,
      'X-Timestamp': timestamp,
      'X-Nonce': nonce,
      'Content-Digest': digest,
      'X-Signature': signature
    };
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
    if (contentType) headers['Content-Type'] = contentType;

    const url = `${this.baseUrl}${path}${rawQuery ? `?${rawQuery}` : ''}`;
    const response = await fetch(url, {
      method: method.toUpperCase(), headers,
      body: bodyBytes.length ? bodyBytes : undefined
    });
    const text = await response.text();
    if (!response.ok) throw new TxnHubError(response.status, text);
    let data = text;
    const ct = response.headers.get('content-type') || '';
    if (ct.includes('application/json') && text) data = JSON.parse(text);
    return { status: response.status, data, headers: response.headers };
  }
}

export function contentDigest(bodyBytes) {
  const digest = createHash('sha256').update(bodyBytes).digest('base64');
  return `sha-256=:${digest}:`;
}

export function canonicalize(method, path, rawQuery, merchantId, keyId, timestamp, nonce, idempotencyKey, digest) {
  return ['PGSIG1', method.toUpperCase(), path, rawQuery || '', merchantId, keyId, timestamp, nonce, idempotencyKey || '', digest].join('\n');
}

export function verifyWebhook({ gatewayPublicKeyPem, eventId, timestamp, contentDigestHeader, signatureBase64, bodyBytes, allowedClockSkewSeconds = 300 }) {
  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - Number(timestamp)) > allowedClockSkewSeconds) return false;
  const calculated = Buffer.from(contentDigest(bodyBytes), 'ascii');
  const supplied = Buffer.from(contentDigestHeader, 'ascii');
  if (calculated.length !== supplied.length || !timingSafeEqual(calculated, supplied)) return false;
  const canonical = ['PGWH1', eventId, timestamp, contentDigestHeader].join('\n');
  const publicKey = createPublicKey(gatewayPublicKeyPem);
  return edVerify(null, Buffer.from(canonical, 'utf8'), publicKey, Buffer.from(signatureBase64, 'base64'));
}
