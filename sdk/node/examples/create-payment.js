import { TxnHubClient } from '../src/index.js';

const client = await TxnHubClient.fromPrivateKeyFile({
  baseUrl: process.env.TXNHUB_BASE_URL || 'http://localhost:8086',
  merchantId: process.env.TXNHUB_MERCHANT_ID,
  keyId: process.env.TXNHUB_KEY_ID,
  privateKeyPath: process.env.TXNHUB_PRIVATE_KEY_PATH
});

const result = await client.createPayment({
  merchantOrderId: `ORDER-${Date.now()}`,
  amountMinor: 2000,
  currency: 'MMK',
  description: 'Node.js SDK payment',
  providerCode: 'ABANK_APLUS',
  providerOptions: {}
});
console.log(result.status, result.data);
