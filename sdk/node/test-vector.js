import { readFile } from 'node:fs/promises';
import { createPrivateKey, sign } from 'node:crypto';
import { canonicalize, contentDigest } from './src/index.js';

const vector = JSON.parse(await readFile('./test-vectors/vector.json', 'utf8'));
const privatePem = await readFile('./test-vectors/test-private-key.pem', 'utf8');
const body = Buffer.from(vector.bodyUtf8, 'utf8');
const digest = contentDigest(body);
if (digest !== vector.contentDigest) throw new Error('Digest mismatch');
const canonical = canonicalize(vector.method, vector.rawPath, vector.rawQuery, vector.merchantId, vector.keyId, vector.timestamp, vector.nonce, vector.idempotencyKey, digest);
if (canonical !== vector.canonical) throw new Error('Canonical mismatch');
const signature = sign(null, Buffer.from(canonical), createPrivateKey(privatePem)).toString('base64');
if (signature !== vector.signatureBase64) throw new Error('Signature mismatch');
console.log('TXN HUB Node SDK conformance vector PASS');
