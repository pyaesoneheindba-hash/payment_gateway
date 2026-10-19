<?php
require __DIR__ . '/src/TxnHubClient.php';
use TxnHub\TxnHubClient;
use TxnHub\PemEd25519;
$vector = json_decode(file_get_contents(__DIR__ . '/test-vectors/vector.json'), true, flags: JSON_THROW_ON_ERROR);
$body = $vector['bodyUtf8'];
$digest = TxnHubClient::contentDigest($body);
if ($digest !== $vector['contentDigest']) throw new RuntimeException('Digest mismatch');
$canonical = TxnHubClient::canonicalize($vector['method'],$vector['rawPath'],$vector['rawQuery'],$vector['merchantId'],$vector['keyId'],$vector['timestamp'],$vector['nonce'],$vector['idempotencyKey'],$digest);
if ($canonical !== $vector['canonical']) throw new RuntimeException('Canonical mismatch');
$pem=file_get_contents(__DIR__ . '/test-vectors/test-private-key.pem');
$seed=PemEd25519::privateSeed($pem);
$pair=sodium_crypto_sign_seed_keypair($seed);
$secret=sodium_crypto_sign_secretkey($pair);
$sig=base64_encode(sodium_crypto_sign_detached($canonical,$secret));
if ($sig !== $vector['signatureBase64']) throw new RuntimeException('Signature mismatch');
echo "TXN HUB PHP SDK conformance vector PASS\n";
