using TxnHub.Sdk;

var client = TxnHubClient.FromPrivateKeyFile(
    Environment.GetEnvironmentVariable("TXNHUB_BASE_URL") ?? "http://localhost:8086",
    Environment.GetEnvironmentVariable("TXNHUB_MERCHANT_ID")!,
    Environment.GetEnvironmentVariable("TXNHUB_KEY_ID")!,
    Environment.GetEnvironmentVariable("TXNHUB_PRIVATE_KEY_PATH")!);

var response = await client.CreatePaymentAsync(new {
    merchantOrderId = $"ORDER-{DateTimeOffset.UtcNow.ToUnixTimeSeconds()}",
    amountMinor = 2000,
    currency = "MMK",
    description = ".NET SDK payment",
    providerCode = "ABANK_APLUS",
    providerOptions = new { }
});
Console.WriteLine(response.Body);
