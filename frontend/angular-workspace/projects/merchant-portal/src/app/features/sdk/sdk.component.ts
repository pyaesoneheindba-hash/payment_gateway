import {ChangeDetectorRef, Component, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {PortalApiService, PortalMe} from '../../core/http/portal-api.service';
import {PortalSessionService} from '../../core/auth/portal-session.service';

interface SdkCard {
  id:string; name:string; runtime:string; detail:string; file:string; badge:string;
}

@Component({
  selector:'app-sdk',
  standalone:true,
  imports:[CommonModule],
  template:`
    <section class="page-heading">
      <div>
        <div class="page-kicker">Developer integration</div>
        <h2>TXN HUB Merchant SDKs</h2>
        <p>Download a server-side SDK that signs and sends payment requests directly to TXN HUB.</p>
      </div>
      <a class="btn secondary" href="/assets/sdk/txnhub-sdk-all-1.0.0.zip" download>Download all SDKs</a>
    </section>

    <section class="card sdk-hero">
      <div>
        <span class="sdk-eyebrow">One API request — signing happens locally</span>
        <h3>Your application does not request a curl command first.</h3>
        <p>The SDK builds the exact request body, calculates <span class="mono">Content-Digest</span>, creates a timestamp and nonce, signs the PGSIG1 canonical input with your Ed25519 private key, and sends the signed HTTP request to the Gateway in one call.</p>
      </div>
      <div class="sdk-flow" aria-label="SDK request flow">
        <div><strong>1</strong><span>Build payment</span></div><i>→</i>
        <div><strong>2</strong><span>Sign locally</span></div><i>→</i>
        <div><strong>3</strong><span>POST Gateway</span></div><i>→</i>
        <div><strong>4</strong><span>Receive QR</span></div>
      </div>
    </section>

    <section class="sdk-credentials card">
      <div class="panel-title">
        <div><h3>Your integration configuration</h3><span class="muted">Merchant ID and active Key ID are populated from your authenticated TXN HUB account.</span></div>
        <span class="pill" *ngIf="integrationReady">Ready for SDK</span>
      </div>
      <div class="credential-grid">
        <div>
          <span>Merchant ID</span>
          <div class="credential-value"><code>{{me?.merchantId || 'Loading…'}}</code><button type="button" class="copy-mini" [disabled]="!me?.merchantId" (click)="copyValue(me?.merchantId || '', 'Merchant ID')">Copy</button></div>
        </div>
        <div>
          <span>Active Key ID</span>
          <div class="credential-value">
            <code [class.error]="!!keysLoadError">{{activeKeyDisplay}}</code>
            <button *ngIf="keysLoadError" type="button" class="copy-mini" (click)="loadKeys()">Retry</button>
            <button *ngIf="!keysLoadError" type="button" class="copy-mini" [disabled]="keysLoading || !activeKeyId" (click)="copyValue(activeKeyId, 'Key ID')">Copy</button>
          </div>
        </div>
        <div>
          <span>Gateway base URL</span>
          <div class="credential-value"><code>{{gatewayBaseUrl}}</code><button type="button" class="copy-mini" (click)="copyValue(gatewayBaseUrl, 'Gateway URL')">Copy</button></div>
        </div>
        <div>
          <span>Private key</span>
          <code>Merchant-owned file / KMS / HSM — never downloaded from Gateway</code>
        </div>
      </div>

      <div class="sdk-config-download">
        <div>
          <strong>Merchant-specific SDK configuration</strong>
          <p>The SDK ZIP is generic. Download this configuration separately; it contains your Merchant ID and active Key ID, but never your private key.</p>
        </div>
        <div class="sdk-config-actions">
          <button class="btn secondary" type="button" [disabled]="!integrationReady" (click)="copySdkConfig()">Copy config</button>
          <button class="btn secondary" type="button" [disabled]="!integrationReady" (click)="downloadEnvConfig()">Download .env</button>
          <button class="btn" type="button" [disabled]="!integrationReady" (click)="downloadJsonConfig()">Download JSON</button>
        </div>
      </div>
      <p class="config-message" *ngIf="configMessage">{{configMessage}}</p>
      <div class="security-note sdk-config-note">
        <strong>Private key is intentionally excluded.</strong> Point <span class="mono">TXNHUB_PRIVATE_KEY_PATH</span> to the PEM file the merchant downloaded when generating the Ed25519 key pair.
      </div>
    </section>

    <section class="sdk-section-heading">
      <div><div class="page-kicker">Choose your backend</div><h3>Download SDK</h3></div>
      <span class="sdk-doc-link">PGSIG1 signing protocol is documented in the project's PROJECT_STATE.md and bundled SDK examples.</span>
    </section>

    <section class="sdk-grid">
      <article class="card sdk-card" *ngFor="let sdk of sdks">
        <div class="sdk-card-top"><span class="sdk-language">{{sdk.name}}</span><span class="pill">v1.0.0</span></div>
        <div class="sdk-runtime">{{sdk.runtime}}</div>
        <p>{{sdk.detail}}</p>
        <div class="sdk-card-actions">
          <button class="btn secondary" type="button" (click)="selectSdk(sdk.id)">Quick start</button>
          <a class="btn" [href]="'/assets/sdk/'+sdk.file" download>Download ZIP</a>
        </div>
      </article>
    </section>

    <section class="card sdk-quickstart">
      <div class="panel-title">
        <div><h3>{{selectedSdkName}} quick start</h3><span class="muted">Use environment variables or a secrets manager for credentials.</span></div>
        <div class="sdk-tabs">
          <button *ngFor="let sdk of sdks" type="button" [class.active]="selected===sdk.id" (click)="selectSdk(sdk.id)">{{sdk.badge}}</button>
        </div>
      </div>
      <pre class="sdk-code"><code>{{snippets[selected]}}</code></pre>
      <div class="security-note">
        <strong>Retry rule:</strong> if the network fails and you retry the same business operation, reuse the same <span class="mono">Idempotency-Key</span> and request body, but let the SDK generate a fresh timestamp, nonce and signature.
      </div>
    </section>

    <section class="sdk-two-col">
      <div class="card">
        <h3>What each SDK handles</h3>
        <ul class="sdk-checklist">
          <li>PKCS#8 Ed25519 private-key loading</li>
          <li>SHA-256 <span class="mono">Content-Digest</span></li>
          <li>PGSIG1 canonical request construction</li>
          <li>Fresh timestamp and replay-protection nonce</li>
          <li>Ed25519 signature and security headers</li>
          <li>Payment, get, reconcile, refund and void helpers</li>
          <li>PGWH1 webhook signature verification</li>
        </ul>
      </div>
      <div class="card">
        <h3>Private-key security</h3>
        <ul class="sdk-checklist warning-list">
          <li>Never paste the private key into the Merchant Portal again after generation.</li>
          <li>Never commit it to Git or package it inside a mobile/browser application.</li>
          <li>Use the SDK only from a trusted merchant backend.</li>
          <li>Prefer KMS/HSM/secrets-manager custody in production.</li>
          <li>Register a new public key before rotating/revoking the old key.</li>
        </ul>
      </div>
    </section>

    <section class="card table-card sdk-endpoints">
      <div class="panel-title"><h3>Common API operations</h3><span class="muted">All protected operations are signed by the SDK</span></div>
      <div class="table-wrap">
        <table>
          <tr><th>SDK method</th><th>HTTP request</th><th>Idempotency key</th></tr>
          <tr><td><code>createPayment</code></td><td><code>POST /api/v1/payments</code></td><td>Required</td></tr>
          <tr><td><code>getPayment</code></td><td><code>GET /api/v1/payments/&#123;paymentId&#125;</code></td><td>Not required</td></tr>
          <tr><td><code>reconcilePayment</code></td><td><code>POST /api/v1/payments/&#123;paymentId&#125;/reconcile</code></td><td>Not required</td></tr>
          <tr><td><code>refundPayment</code></td><td><code>POST /api/v1/payments/&#123;paymentId&#125;/refunds</code></td><td>Required</td></tr>
          <tr><td><code>voidPayment</code></td><td><code>POST /api/v1/payments/&#123;paymentId&#125;/void</code></td><td>Required</td></tr>
        </table>
      </div>
    </section>
  `
})
export class SdkComponent implements OnInit {
  me?:PortalMe;
  keys:any[]=[];
  activeKeyId='';
  keysLoading=true;
  keysLoadError='';
  configMessage='';
  readonly gatewayBaseUrl=((window as any).__PAYMENT_PORTAL_CONFIG__?.gatewayBaseUrl || 'http://localhost:8086').replace(/\/$/,'');
  readonly privateKeyPathPlaceholder='/secure/path/txnhub-private.pem';
  selected='java';
  sdks:SdkCard[]=[
    {id:'java',name:'Java',badge:'Java',runtime:'JDK 17+',detail:'JDK Ed25519 + HttpClient. Jackson is used for JSON convenience.',file:'txnhub-java-sdk-1.0.0.zip'},
    {id:'php',name:'PHP',badge:'PHP',runtime:'PHP 8.1+',detail:'Uses the sodium and curl extensions. Supports the portal-generated PKCS#8 key.',file:'txnhub-php-sdk-1.0.0.zip'},
    {id:'node',name:'Node.js',badge:'Node',runtime:'Node.js 18+',detail:'Zero runtime dependencies; uses built-in crypto and fetch.',file:'txnhub-node-sdk-1.0.0.zip'},
    {id:'dotnet',name:'.NET',badge:'.NET',runtime:'.NET 8+',detail:'HttpClient integration with BouncyCastle Ed25519 support.',file:'txnhub-dotnet-sdk-1.0.0.zip'},
    {id:'python',name:'Python',badge:'Python',runtime:'Python 3.10+',detail:'Uses cryptography for Ed25519 and standard-library HTTP transport.',file:'txnhub-python-sdk-1.0.0.zip'}
  ];

  snippets:Record<string,string>={
java:`TxnHubClient client = new TxnHubClient(
    "http://localhost:8086",
    System.getenv("TXNHUB_MERCHANT_ID"),
    System.getenv("TXNHUB_KEY_ID"),
    Path.of(System.getenv("TXNHUB_PRIVATE_KEY_PATH"))
);

TxnHubResponse response = client.createPayment(Map.of(
    "merchantOrderId", "ORDER-10001",
    "amountMinor", 2000,
    "currency", "MMK",
    "description", "Merchant payment",
    "providerCode", "ABANK_APLUS",
    "providerOptions", Map.of()
), TxnHubClient.newIdempotencyKey());`,
php:`$client = TxnHubClient::fromPrivateKeyFile(
    'http://localhost:8086',
    getenv('TXNHUB_MERCHANT_ID'),
    getenv('TXNHUB_KEY_ID'),
    getenv('TXNHUB_PRIVATE_KEY_PATH')
);

$result = $client->createPayment([
    'merchantOrderId' => 'ORDER-10001',
    'amountMinor' => 2000,
    'currency' => 'MMK',
    'description' => 'Merchant payment',
    'providerCode' => 'ABANK_APLUS',
    'providerOptions' => []
]);`,
node:`const client = await TxnHubClient.fromPrivateKeyFile({
  baseUrl: 'http://localhost:8086',
  merchantId: process.env.TXNHUB_MERCHANT_ID,
  keyId: process.env.TXNHUB_KEY_ID,
  privateKeyPath: process.env.TXNHUB_PRIVATE_KEY_PATH
});

const result = await client.createPayment({
  merchantOrderId: 'ORDER-10001', amountMinor: 2000,
  currency: 'MMK', description: 'Merchant payment',
  providerCode: 'ABANK_APLUS', providerOptions: {}
});`,
dotnet:`var client = TxnHubClient.FromPrivateKeyFile(
    "http://localhost:8086",
    Environment.GetEnvironmentVariable("TXNHUB_MERCHANT_ID")!,
    Environment.GetEnvironmentVariable("TXNHUB_KEY_ID")!,
    Environment.GetEnvironmentVariable("TXNHUB_PRIVATE_KEY_PATH")!);

var response = await client.CreatePaymentAsync(new {
    merchantOrderId = "ORDER-10001", amountMinor = 2000,
    currency = "MMK", description = "Merchant payment",
    providerCode = "ABANK_APLUS", providerOptions = new { }
});`,
python:`client = TxnHubClient.from_private_key_file(
    "http://localhost:8086",
    os.environ["TXNHUB_MERCHANT_ID"],
    os.environ["TXNHUB_KEY_ID"],
    os.environ["TXNHUB_PRIVATE_KEY_PATH"]
)

result = client.create_payment({
    "merchantOrderId": "ORDER-10001", "amountMinor": 2000,
    "currency": "MMK", "description": "Merchant payment",
    "providerCode": "ABANK_APLUS", "providerOptions": {}
})`
  };

  constructor(private api:PortalApiService,private cdr:ChangeDetectorRef,private session:PortalSessionService){}
  ngOnInit(){
    this.me=this.session.current;
    this.loadKeys();
  }
  loadKeys(){
    this.keysLoading=true;
    this.keysLoadError='';
    this.activeKeyId='';
    this.cdr.detectChanges();
    this.api.keys().subscribe({
      next:x=>{
        this.keys=x||[];
        const active=this.keys.find(k=>String(k.status||'').toUpperCase()==='ACTIVE');
        this.activeKeyId=active?.key_id||'';
        this.keysLoading=false;
        this.cdr.detectChanges();
      },
      error:()=>{
        this.keys=[];
        this.activeKeyId='';
        this.keysLoading=false;
        this.keysLoadError='Unable to load active key';
        this.cdr.detectChanges();
      }
    });
  }
  get selectedSdkName(){return this.sdks.find(x=>x.id===this.selected)?.name||'SDK';}
  get activeKeyDisplay(){
    if(this.keysLoading)return 'Loading active key…';
    if(this.keysLoadError)return this.keysLoadError;
    return this.activeKeyId||'No active Ed25519 key';
  }
  get integrationReady(){return !this.keysLoading && !this.keysLoadError && !!this.me?.merchantId && !!this.activeKeyId;}
  get envConfig(){
    if(!this.integrationReady) return '';
    return [
      `TXNHUB_BASE_URL=${this.gatewayBaseUrl}`,
      `TXNHUB_MERCHANT_ID=${this.me!.merchantId}`,
      `TXNHUB_KEY_ID=${this.activeKeyId}`,
      `TXNHUB_PRIVATE_KEY_PATH=${this.privateKeyPathPlaceholder}`,
      ''
    ].join('\n');
  }
  get jsonConfig(){
    if(!this.integrationReady) return '';
    return JSON.stringify({
      baseUrl:this.gatewayBaseUrl,
      merchantId:this.me!.merchantId,
      keyId:this.activeKeyId,
      privateKeyPath:this.privateKeyPathPlaceholder
    },null,2)+'\n';
  }
  selectSdk(id:string){this.selected=id;setTimeout(()=>document.querySelector('.sdk-quickstart')?.scrollIntoView({behavior:'smooth',block:'start'}),0);}
  async copyValue(value:string,label:string){
    if(!value) return;
    await this.copyText(value);
    this.configMessage=`${label} copied.`;
    this.cdr.detectChanges();
  }
  async copySdkConfig(){
    if(!this.envConfig) return;
    await this.copyText(this.envConfig);
    this.configMessage='SDK configuration copied. Add the private-key path on your merchant server.';
    this.cdr.detectChanges();
  }
  downloadEnvConfig(){
    if(!this.envConfig) return;
    this.downloadText('txnhub.env',this.envConfig,'text/plain;charset=utf-8');
    this.configMessage='txnhub.env downloaded. It does not contain your private key.';
    this.cdr.detectChanges();
  }
  downloadJsonConfig(){
    if(!this.jsonConfig) return;
    this.downloadText('txnhub-config.json',this.jsonConfig,'application/json;charset=utf-8');
    this.configMessage='txnhub-config.json downloaded. It does not contain your private key.';
    this.cdr.detectChanges();
  }
  private async copyText(value:string){
    if(navigator.clipboard?.writeText){
      try{await navigator.clipboard.writeText(value);return;}catch{}
    }
    const area=document.createElement('textarea');
    area.value=value;area.style.position='fixed';area.style.opacity='0';
    document.body.appendChild(area);area.select();document.execCommand('copy');area.remove();
  }
  private downloadText(fileName:string,content:string,type:string){
    const blob=new Blob([content],{type});
    const url=URL.createObjectURL(blob);
    const a=document.createElement('a');
    a.href=url;a.download=fileName;document.body.appendChild(a);a.click();a.remove();
    setTimeout(()=>URL.revokeObjectURL(url),0);
  }
}
