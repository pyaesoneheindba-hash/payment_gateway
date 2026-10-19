using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;
using Org.BouncyCastle.OpenSsl;
using Org.BouncyCastle.Pkcs;
using Org.BouncyCastle.Security;

namespace TxnHub.Sdk;

public sealed class TxnHubException : Exception
{
    public int StatusCode { get; }
    public string ResponseBody { get; }
    public TxnHubException(int statusCode, string responseBody)
        : base($"TXN HUB request failed with HTTP {statusCode}: {responseBody}")
    { StatusCode = statusCode; ResponseBody = responseBody; }
}

public sealed record TxnHubResponse(int StatusCode, string Body, HttpResponseHeaders Headers)
{
    public T? Json<T>() => JsonSerializer.Deserialize<T>(Body);
}

public sealed class TxnHubClient
{
    private readonly string _baseUrl;
    private readonly string _merchantId;
    private readonly string _keyId;
    private readonly Ed25519PrivateKeyParameters _privateKey;
    private readonly HttpClient _http;

    public TxnHubClient(string baseUrl, string merchantId, string keyId, string privateKeyPem, HttpClient? httpClient = null)
    {
        _baseUrl = baseUrl.TrimEnd('/');
        _merchantId = Guid.Parse(merchantId).ToString();
        _keyId = keyId;
        _privateKey = ReadPrivateKey(privateKeyPem);
        _http = httpClient ?? new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
    }

    public static TxnHubClient FromPrivateKeyFile(string baseUrl, string merchantId, string keyId, string privateKeyPath, HttpClient? httpClient = null)
        => new(baseUrl, merchantId, keyId, File.ReadAllText(privateKeyPath), httpClient);

    public static string NewIdempotencyKey() => Guid.NewGuid().ToString();

    public Task<TxnHubResponse> CreatePaymentAsync(object body, string? idempotencyKey = null, CancellationToken ct = default)
        => PostJsonAsync("/api/v1/payments", body, idempotencyKey ?? NewIdempotencyKey(), ct);

    public Task<TxnHubResponse> GetPaymentAsync(Guid paymentId, CancellationToken ct = default)
        => RequestAsync(HttpMethod.Get, $"/api/v1/payments/{paymentId}", cancellationToken: ct);

    public Task<TxnHubResponse> ReconcilePaymentAsync(Guid paymentId, CancellationToken ct = default)
        => RequestAsync(HttpMethod.Post, $"/api/v1/payments/{paymentId}/reconcile", cancellationToken: ct);

    public Task<TxnHubResponse> RefundPaymentAsync(Guid paymentId, object body, string? idempotencyKey = null, CancellationToken ct = default)
        => PostJsonAsync($"/api/v1/payments/{paymentId}/refunds", body, idempotencyKey ?? NewIdempotencyKey(), ct);

    public Task<TxnHubResponse> VoidPaymentAsync(Guid paymentId, object? providerOptions = null, string? idempotencyKey = null, CancellationToken ct = default)
        => PostJsonAsync($"/api/v1/payments/{paymentId}/void", new { providerOptions = providerOptions ?? new { } }, idempotencyKey ?? NewIdempotencyKey(), ct);

    public Task<TxnHubResponse> PostJsonAsync(string path, object body, string idempotencyKey, CancellationToken ct = default)
    {
        byte[] bytes = JsonSerializer.SerializeToUtf8Bytes(body);
        return RequestAsync(HttpMethod.Post, path, body: bytes, idempotencyKey: idempotencyKey, contentType: "application/json", cancellationToken: ct);
    }

    public async Task<TxnHubResponse> RequestAsync(HttpMethod method, string path, string rawQuery = "", byte[]? body = null,
        string idempotencyKey = "", string? contentType = null, CancellationToken cancellationToken = default)
    {
        body ??= Array.Empty<byte>();
        string timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString();
        string nonce = Guid.NewGuid().ToString();
        string digest = ContentDigest(body);
        string canonical = Canonicalize(method.Method, path, rawQuery, _merchantId, _keyId, timestamp, nonce, idempotencyKey, digest);
        string signature = Convert.ToBase64String(Sign(_privateKey, Encoding.UTF8.GetBytes(canonical)));
        string url = _baseUrl + path + (string.IsNullOrEmpty(rawQuery) ? "" : "?" + rawQuery);

        using var request = new HttpRequestMessage(method, url);
        request.Headers.TryAddWithoutValidation("X-Merchant-Id", _merchantId);
        request.Headers.TryAddWithoutValidation("X-Key-Id", _keyId);
        request.Headers.TryAddWithoutValidation("X-Timestamp", timestamp);
        request.Headers.TryAddWithoutValidation("X-Nonce", nonce);
        request.Headers.TryAddWithoutValidation("Content-Digest", digest);
        request.Headers.TryAddWithoutValidation("X-Signature", signature);
        if (!string.IsNullOrEmpty(idempotencyKey)) request.Headers.TryAddWithoutValidation("Idempotency-Key", idempotencyKey);
        if (body.Length > 0)
        {
            request.Content = new ByteArrayContent(body);
            if (contentType is not null) request.Content.Headers.ContentType = MediaTypeHeaderValue.Parse(contentType);
        }

        using HttpResponseMessage response = await _http.SendAsync(request, cancellationToken);
        string responseBody = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode) throw new TxnHubException((int)response.StatusCode, responseBody);
        return new TxnHubResponse((int)response.StatusCode, responseBody, response.Headers);
    }

    public static string ContentDigest(byte[] body)
        => "sha-256=:" + Convert.ToBase64String(SHA256.HashData(body)) + ":";

    public static string Canonicalize(string method, string path, string rawQuery, string merchantId, string keyId,
        string timestamp, string nonce, string idempotencyKey, string digest)
        => string.Join("\n", "PGSIG1", method.ToUpperInvariant(), path, rawQuery ?? "", merchantId, keyId,
            timestamp, nonce, idempotencyKey ?? "", digest);

    private static byte[] Sign(Ed25519PrivateKeyParameters key, byte[] message)
    {
        var signer = new Ed25519Signer();
        signer.Init(true, key);
        signer.BlockUpdate(message, 0, message.Length);
        return signer.GenerateSignature();
    }

    private static Ed25519PrivateKeyParameters ReadPrivateKey(string pem)
    {
        using var reader = new StringReader(pem);
        object obj = new PemReader(reader).ReadObject();
        if (obj is Ed25519PrivateKeyParameters direct) return direct;
        if (obj is Org.BouncyCastle.Crypto.AsymmetricCipherKeyPair pair && pair.Private is Ed25519PrivateKeyParameters p) return p;
        if (obj is Org.BouncyCastle.Asn1.Pkcs.PrivateKeyInfo info)
            return (Ed25519PrivateKeyParameters)PrivateKeyFactory.CreateKey(info);
        throw new InvalidOperationException("Expected an Ed25519 PKCS#8 private key");
    }
}
