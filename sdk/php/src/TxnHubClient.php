<?php
declare(strict_types=1);

namespace TxnHub;

use RuntimeException;

final class TxnHubException extends RuntimeException
{
    public function __construct(public readonly int $statusCode, public readonly string $responseBody)
    {
        parent::__construct("TXN HUB request failed with HTTP {$statusCode}: {$responseBody}");
    }
}

final class TxnHubClient
{
    private string $baseUrl;
    private string $secretKey;

    public function __construct(
        string $baseUrl,
        private readonly string $merchantId,
        private readonly string $keyId,
        string $privateKeyPem
    ) {
        if (!extension_loaded('sodium')) throw new RuntimeException('PHP sodium extension is required');
        if (!extension_loaded('curl')) throw new RuntimeException('PHP curl extension is required');
        $this->baseUrl = rtrim($baseUrl, '/');
        $seed = PemEd25519::privateSeed($privateKeyPem);
        $pair = sodium_crypto_sign_seed_keypair($seed);
        $this->secretKey = sodium_crypto_sign_secretkey($pair);
    }

    public static function fromPrivateKeyFile(string $baseUrl, string $merchantId, string $keyId, string $privateKeyPath): self
    {
        $pem = @file_get_contents($privateKeyPath);
        if ($pem === false) throw new RuntimeException("Cannot read private key: {$privateKeyPath}");
        return new self($baseUrl, $merchantId, $keyId, $pem);
    }

    public static function newIdempotencyKey(): string
    {
        $b = random_bytes(16);
        $b[6] = chr((ord($b[6]) & 0x0f) | 0x40);
        $b[8] = chr((ord($b[8]) & 0x3f) | 0x80);
        $h = bin2hex($b);
        return substr($h,0,8).'-'.substr($h,8,4).'-'.substr($h,12,4).'-'.substr($h,16,4).'-'.substr($h,20);
    }

    public function createPayment(array $request, ?string $idempotencyKey = null): array
    {
        return $this->postJson('/api/v1/payments', $request, $idempotencyKey ?? self::newIdempotencyKey());
    }

    public function getPayment(string $paymentId): array
    {
        return $this->request('GET', "/api/v1/payments/{$paymentId}");
    }

    public function reconcilePayment(string $paymentId): array
    {
        return $this->request('POST', "/api/v1/payments/{$paymentId}/reconcile");
    }

    public function refundPayment(string $paymentId, array $request, ?string $idempotencyKey = null): array
    {
        return $this->postJson("/api/v1/payments/{$paymentId}/refunds", $request, $idempotencyKey ?? self::newIdempotencyKey());
    }

    public function voidPayment(string $paymentId, array $providerOptions = [], ?string $idempotencyKey = null): array
    {
        return $this->postJson("/api/v1/payments/{$paymentId}/void", ['providerOptions' => $providerOptions], $idempotencyKey ?? self::newIdempotencyKey());
    }

    public function postJson(string $path, array $body, string $idempotencyKey): array
    {
        $json = json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
        return $this->request('POST', $path, '', $json, $idempotencyKey, 'application/json');
    }

    public function request(string $method, string $path, string $rawQuery = '', string $body = '', string $idempotencyKey = '', ?string $contentType = null): array
    {
        $timestamp = (string) time();
        $nonce = self::newIdempotencyKey();
        $digest = self::contentDigest($body);
        $canonical = self::canonicalize($method, $path, $rawQuery, $this->merchantId, $this->keyId, $timestamp, $nonce, $idempotencyKey, $digest);
        $signature = base64_encode(sodium_crypto_sign_detached($canonical, $this->secretKey));
        $headers = [
            "X-Merchant-Id: {$this->merchantId}", "X-Key-Id: {$this->keyId}",
            "X-Timestamp: {$timestamp}", "X-Nonce: {$nonce}",
            "Content-Digest: {$digest}", "X-Signature: {$signature}"
        ];
        if ($idempotencyKey !== '') $headers[] = "Idempotency-Key: {$idempotencyKey}";
        if ($contentType !== null) $headers[] = "Content-Type: {$contentType}";

        $url = $this->baseUrl . $path . ($rawQuery === '' ? '' : '?' . $rawQuery);
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_CUSTOMREQUEST => strtoupper($method), CURLOPT_HTTPHEADER => $headers,
            CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 30, CURLOPT_CONNECTTIMEOUT => 10,
            CURLOPT_HEADER => false
        ]);
        if ($body !== '') curl_setopt($ch, CURLOPT_POSTFIELDS, $body);
        $responseBody = curl_exec($ch);
        if ($responseBody === false) throw new RuntimeException('TXN HUB HTTP error: ' . curl_error($ch));
        $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);
        if ($status < 200 || $status >= 300) throw new TxnHubException($status, (string)$responseBody);
        $decoded = json_decode((string)$responseBody, true);
        return ['status' => $status, 'data' => $decoded ?? (string)$responseBody];
    }

    public static function contentDigest(string $body): string
    {
        return 'sha-256=:' . base64_encode(hash('sha256', $body, true)) . ':';
    }

    public static function canonicalize(string $method, string $path, string $rawQuery, string $merchantId, string $keyId, string $timestamp, string $nonce, string $idempotencyKey, string $digest): string
    {
        return implode("\n", ['PGSIG1', strtoupper($method), $path, $rawQuery, $merchantId, $keyId, $timestamp, $nonce, $idempotencyKey, $digest]);
    }
}

final class TxnHubWebhookVerifier
{
    public static function verify(string $gatewayPublicKeyPem, string $eventId, string $timestamp, string $contentDigestHeader, string $signatureBase64, string $body, int $allowedClockSkewSeconds = 300): bool
    {
        if (abs(time() - (int)$timestamp) > $allowedClockSkewSeconds) return false;
        $calculated = TxnHubClient::contentDigest($body);
        if (!hash_equals($calculated, $contentDigestHeader)) return false;
        $rawPublic = PemEd25519::publicKey($gatewayPublicKeyPem);
        $canonical = implode("\n", ['PGWH1', $eventId, $timestamp, $contentDigestHeader]);
        $sig = base64_decode($signatureBase64, true);
        return $sig !== false && sodium_crypto_sign_verify_detached($sig, $canonical, $rawPublic);
    }
}

final class PemEd25519
{
    public static function privateSeed(string $pem): string
    {
        $der = self::decodePem($pem, 'PRIVATE KEY');
        $rootOffset = 0;
        [$tag, $outer] = self::readTlv($der, $rootOffset);
        if ($tag !== 0x30) throw new RuntimeException('Invalid PKCS#8 private key');
        $o = 0;
        self::readTlv($outer, $o); // version
        self::readTlv($outer, $o); // algorithm
        [$tag, $privateOctets] = self::readTlv($outer, $o);
        if ($tag !== 0x04) throw new RuntimeException('Invalid Ed25519 PKCS#8 private key');
        $i = 0;
        [$innerTag, $seed] = self::readTlv($privateOctets, $i);
        if ($innerTag !== 0x04 || strlen($seed) !== SODIUM_CRYPTO_SIGN_SEEDBYTES) throw new RuntimeException('Invalid Ed25519 seed');
        return $seed;
    }

    public static function publicKey(string $pem): string
    {
        $der = self::decodePem($pem, 'PUBLIC KEY');
        $rootOffset = 0;
        [$tag, $outer] = self::readTlv($der, $rootOffset);
        if ($tag !== 0x30) throw new RuntimeException('Invalid SubjectPublicKeyInfo');
        $o = 0;
        self::readTlv($outer, $o); // algorithm
        [$bitTag, $bits] = self::readTlv($outer, $o);
        if ($bitTag !== 0x03 || strlen($bits) !== 33 || ord($bits[0]) !== 0) throw new RuntimeException('Invalid Ed25519 public key');
        return substr($bits, 1);
    }

    private static function decodePem(string $pem, string $label): string
    {
        $b64 = str_replace(["-----BEGIN {$label}-----", "-----END {$label}-----", "\r", "\n", ' ', "\t"], '', $pem);
        $der = base64_decode($b64, true);
        if ($der === false) throw new RuntimeException("Invalid {$label} PEM");
        return $der;
    }

    /** @return array{int,string} */
    private static function readTlv(string $data, int &$offset): array
    {
        if ($offset >= strlen($data)) throw new RuntimeException('Invalid DER');
        $tag = ord($data[$offset++]);
        $first = ord($data[$offset++]);
        if (($first & 0x80) === 0) $length = $first;
        else {
            $n = $first & 0x7f;
            if ($n < 1 || $n > 4 || $offset + $n > strlen($data)) throw new RuntimeException('Invalid DER length');
            $length = 0;
            for ($i=0; $i<$n; $i++) $length = ($length << 8) | ord($data[$offset++]);
        }
        if ($offset + $length > strlen($data)) throw new RuntimeException('Truncated DER');
        $value = substr($data, $offset, $length);
        $offset += $length;
        return [$tag, $value];
    }
}
