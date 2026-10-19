import {ChangeDetectorRef, Component, OnDestroy, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {PortalApiService, PortalMe} from '../../core/http/portal-api.service';
import {PortalSessionService} from '../../core/auth/portal-session.service';

@Component({
  selector:'app-integrations',
  standalone:true,
  imports:[CommonModule,FormsModule],
  template:`
  <section class="page-heading">
    <div>
      <div class="page-kicker">Integration security</div>
      <h2>API Keys & Webhooks</h2>
      <p>Manage Ed25519 signing keys and merchant callback endpoints.</p>
    </div>
    <span class="muted">Private keys never leave your browser</span>
  </section>
  <div class="grid">
    <div class="card" *ngIf="me?.merchantStatus==='PENDING'">
      <h3>Complete merchant security setup</h3>
      <p class="muted">Your merchant is PENDING. Generate or import an Ed25519 public key here. Payment APIs, transaction data and webhook management remain disabled until a SUPER_ADMIN reviews and activates the merchant.</p>
    </div>

    <div class="card">
      <div class="section-title">
        <div>
          <h3>Ed25519 API signing keys</h3>
          <p class="muted">Only the public key is registered with TXN HUB. The private key is generated in this browser, is never sent to the Gateway, and is never stored in browser storage.</p>
        </div>
        <span class="pill" *ngIf="me?.merchantStatus">{{me.merchantStatus}}</span>
      </div>

      <div class="table-wrap" *ngIf="keys.length; else noKeys">
        <table><tr><th>Key ID</th><th>Status</th><th>Valid from</th></tr><tr *ngFor="let k of keys"><td>{{k.key_id}}</td><td>{{k.status}}</td><td>{{k.valid_from|date}}</td></tr></table>
      </div>
      <ng-template #noKeys><p class="muted">No public signing key is registered yet.</p></ng-template>

      <ng-container *ngIf="me?.role==='MERCHANT_ADMIN'">
        <hr class="divider">
        <h4>Register a signing key</h4>
        <label>Key ID<input class="input" [(ngModel)]="keyId" placeholder="merchant-prod-2026-01"></label>

        <div class="key-actions">
          <button class="btn" type="button" [disabled]="generating" (click)="generateKeyPair()">{{generating ? 'Generating…' : 'Generate Ed25519 key pair in this browser'}}</button>
          <button class="btn secondary" type="button" (click)="clearGeneratedKey()" *ngIf="generatedLocally">Discard generated key</button>
        </div>

        <div class="security-note" *ngIf="generatedLocally">
          <strong>Private key generated locally.</strong> Save it now. TXN HUB does not upload or recover it. Losing this private key means the merchant must register a new public key.
        </div>

        <label>Public key<textarea class="input mono" rows="7" [(ngModel)]="publicKey" placeholder="-----BEGIN PUBLIC KEY-----"></textarea></label>
        <p class="muted" *ngIf="fingerprint">SHA-256 fingerprint: <span class="mono">{{fingerprint}}</span></p>

        <div class="private-key-panel" *ngIf="generatedLocally">
          <div class="section-title">
            <div><strong>Private key — merchant secret</strong><div class="muted">Displayed only from browser memory. It is not included in the API request.</div></div>
            <div class="key-actions compact">
              <button class="btn secondary" type="button" (click)="revealPrivateKey=!revealPrivateKey">{{revealPrivateKey ? 'Hide' : 'Reveal once'}}</button>
              <button class="btn secondary" type="button" (click)="downloadPrivateKey()">Download private key</button>
            </div>
          </div>
          <pre class="private-key mono" *ngIf="revealPrivateKey">{{privateKeyPem}}</pre>
          <label class="confirm-row"><input type="checkbox" [(ngModel)]="privateKeySaved"> I have securely saved the private key.</label>
        </div>

        <div class="key-actions">
          <button class="btn" type="button" [disabled]="registering || !canRegisterKey" (click)="addKey()">{{registering ? 'Registering…' : 'Register public key'}}</button>
        </div>
        <p class="error" *ngIf="keyError">{{keyError}}</p>
        <p class="success" *ngIf="keySuccess">{{keySuccess}}</p>
      </ng-container>
    </div>

    <div class="card" *ngIf="me?.merchantStatus==='ACTIVE'">
      <h3>Webhook endpoints</h3>
      <ng-container *ngIf="canManageWebhook"><input class="input" [(ngModel)]="webhook" placeholder="https://merchant.example.com/webhooks/payment-status"><br><br><button class="btn" (click)="addWebhook()">Add webhook</button><br><br></ng-container>
      <table><tr><th>URL</th><th>Status</th><th>Event</th></tr><tr *ngFor="let w of webhooks"><td>{{w.url}}</td><td>{{w.status}}</td><td>{{w.event_type}}</td></tr></table>
    </div>
  </div>`
})
export class IntegrationsComponent implements OnInit, OnDestroy {
  me?:PortalMe;
  keys:any[]=[];
  webhooks:any[]=[];
  keyId='';
  publicKey='';
  privateKeyPem='';
  fingerprint='';
  webhook='';
  generatedLocally=false;
  revealPrivateKey=false;
  privateKeySaved=false;
  generating=false;
  registering=false;
  keyError='';
  keySuccess='';

  get canManageWebhook(){return this.me?.merchantStatus==='ACTIVE' && (this.me?.role==='MERCHANT_ADMIN'||this.me?.role==='MERCHANT_DEVELOPER');}
  get canRegisterKey(){
    if (!this.keyId.trim() || !this.publicKey.trim()) return false;
    return !this.generatedLocally || this.privateKeySaved;
  }

  constructor(private api:PortalApiService,private cdr:ChangeDetectorRef,private session:PortalSessionService){}

  ngOnInit(){
    this.me=this.session.current;
    if(!this.me){this.keyError='Could not load merchant identity';this.cdr.detectChanges();return;}
    this.loadKeys();
    if(this.me.merchantStatus==='ACTIVE')this.loadWebhooks();
    this.cdr.detectChanges();
  }

  ngOnDestroy(){ this.clearPrivateMaterial(); }

  loadKeys(){this.api.keys().subscribe({next:x=>{this.keys=x;this.cdr.detectChanges();},error:e=>{this.keyError=e?.error?.error||'Could not load API keys';this.cdr.detectChanges();}});}
  loadWebhooks(){this.api.webhooks().subscribe({next:x=>{this.webhooks=x;this.cdr.detectChanges();},error:()=>this.cdr.detectChanges()});}

  async generateKeyPair(){
    this.keyError=''; this.keySuccess=''; this.generating=true;
    try {
      const pair = await crypto.subtle.generateKey({name:'Ed25519'}, true, ['sign','verify']) as CryptoKeyPair;
      const [spki,pkcs8] = await Promise.all([
        crypto.subtle.exportKey('spki', pair.publicKey),
        crypto.subtle.exportKey('pkcs8', pair.privateKey)
      ]);
      this.publicKey=this.toPem('PUBLIC KEY',spki);
      this.privateKeyPem=this.toPem('PRIVATE KEY',pkcs8);
      const digest=await crypto.subtle.digest('SHA-256',spki);
      this.fingerprint=Array.from(new Uint8Array(digest)).map(b=>b.toString(16).padStart(2,'0')).join(':');
      if(!this.keyId.trim()) this.keyId=`merchant-${Date.now()}`;
      this.generatedLocally=true;
      this.privateKeySaved=false;
      this.revealPrivateKey=true;
    }
    catch(e){
      this.keyError='This browser could not generate an Ed25519 key pair. Use a modern browser with Web Crypto Ed25519 support or import an externally generated public key.';
    }
    finally{this.generating=false;this.cdr.detectChanges();}
  }

  downloadPrivateKey(){
    if(!this.privateKeyPem) return;
    const blob=new Blob([this.privateKeyPem],{type:'application/x-pem-file'});
    const url=URL.createObjectURL(blob);
    const a=document.createElement('a');
    a.href=url;
    a.download=`${this.keyId.trim()||'merchant-ed25519'}-private.pem`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(()=>URL.revokeObjectURL(url),0);
  }

  clearGeneratedKey(){
    this.publicKey='';
    this.fingerprint='';
    this.clearPrivateMaterial();
    this.keySuccess='';
    this.keyError='';
    this.cdr.detectChanges();
  }

  addKey(){
    if(!this.canRegisterKey) return;
    this.keyError=''; this.keySuccess=''; this.registering=true;
    const submittedKeyId=this.keyId.trim();
    const submittedPublicKey=this.publicKey.trim();
    this.api.registerKey(submittedKeyId,submittedPublicKey).subscribe({
      next:()=>{
        this.keySuccess='Public key registered. Your private key remained in this browser only and has now been cleared from page memory.';
        this.keyId='';
        this.publicKey='';
        this.fingerprint='';
        this.clearPrivateMaterial();
        this.registering=false;
        this.loadKeys();
        this.cdr.detectChanges();
      },
      error:e=>{
        this.registering=false;
        this.keyError=e?.error?.message||e?.error?.error||'Public key registration failed';
        this.cdr.detectChanges();
      }
    });
  }

  addWebhook(){this.api.addWebhook(this.webhook).subscribe({next:()=>{this.webhook='';this.loadWebhooks();this.cdr.detectChanges();},error:()=>this.cdr.detectChanges()});}

  private clearPrivateMaterial(){
    this.privateKeyPem='';
    this.generatedLocally=false;
    this.revealPrivateKey=false;
    this.privateKeySaved=false;
  }

  private toPem(label:string,buffer:ArrayBuffer):string{
    const bytes=new Uint8Array(buffer);
    let binary='';
    bytes.forEach(b=>binary+=String.fromCharCode(b));
    const b64=btoa(binary);
    const lines=b64.match(/.{1,64}/g)?.join('\n')||b64;
    return `-----BEGIN ${label}-----\n${lines}\n-----END ${label}-----\n`;
  }
}
