<?php
require __DIR__ . '/../src/TxnHubClient.php';
use TxnHub\TxnHubClient;

$client = TxnHubClient::fromPrivateKeyFile(
    getenv('TXNHUB_BASE_URL') ?: 'http://localhost:8086',
    getenv('TXNHUB_MERCHANT_ID'),
    getenv('TXNHUB_KEY_ID'),
    getenv('TXNHUB_PRIVATE_KEY_PATH')
);
$result = $client->createPayment([
    'merchantOrderId' => 'ORDER-' . time(),
    'amountMinor' => 2000,
    'currency' => 'MMK',
    'description' => 'PHP SDK payment',
    'providerCode' => 'ABANK_APLUS',
    'providerOptions' => []
]);
print_r($result);
