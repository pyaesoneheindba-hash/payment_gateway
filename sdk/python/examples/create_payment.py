import os, time
from txnhub_sdk import TxnHubClient

client = TxnHubClient.from_private_key_file(
    os.getenv("TXNHUB_BASE_URL", "http://localhost:8086"),
    os.environ["TXNHUB_MERCHANT_ID"],
    os.environ["TXNHUB_KEY_ID"],
    os.environ["TXNHUB_PRIVATE_KEY_PATH"],
)
result = client.create_payment({
    "merchantOrderId": f"ORDER-{int(time.time())}",
    "amountMinor": 2000,
    "currency": "MMK",
    "description": "Python SDK payment",
    "providerCode": "ABANK_APLUS",
    "providerOptions": {},
})
print(result)
