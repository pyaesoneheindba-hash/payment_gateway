using System.Security.Cryptography;
using System.Text;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;
using Org.BouncyCastle.OpenSsl;
using Org.BouncyCastle.Security;

namespace TxnHub.Sdk;

public static class TxnHubWebhookVerifier
{
    public static bool Verify(string gatewayPublicKeyPem, string eventId, string timestamp, string contentDigestHeader,
        string signatureBase64, byte[] body, long allowedClockSkewSeconds = 300)
    {
        try
        {
            long ts = long.Parse(timestamp);
            if (Math.Abs(DateTimeOffset.UtcNow.ToUnixTimeSeconds() - ts) > allowedClockSkewSeconds) return false;
            string calculated = TxnHubClient.ContentDigest(body);
            if (!CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(calculated), Encoding.ASCII.GetBytes(contentDigestHeader))) return false;
            var key = ReadPublicKey(gatewayPublicKeyPem);
            string canonical = string.Join("\n", "PGWH1", eventId, timestamp, contentDigestHeader);
            var verifier = new Ed25519Signer();
            verifier.Init(false, key);
            byte[] message = Encoding.UTF8.GetBytes(canonical);
            verifier.BlockUpdate(message, 0, message.Length);
            return verifier.VerifySignature(Convert.FromBase64String(signatureBase64));
        }
        catch { return false; }
    }

    private static Ed25519PublicKeyParameters ReadPublicKey(string pem)
    {
        using var reader = new StringReader(pem);
        object obj = new PemReader(reader).ReadObject();
        if (obj is Ed25519PublicKeyParameters p) return p;
        if (obj is Org.BouncyCastle.Asn1.X509.SubjectPublicKeyInfo info)
            return (Ed25519PublicKeyParameters)PublicKeyFactory.CreateKey(info);
        throw new InvalidOperationException("Expected an Ed25519 public key");
    }
}
