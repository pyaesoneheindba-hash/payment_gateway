import {ChangeDetectorRef,Component,OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {MerchantOnboardingRequest,MerchantPortalLoginRepairRequest,PortalApiService,PortalMe} from '../../core/http/portal-api.service';
import {PortalSessionService} from '../../core/auth/portal-session.service';

@Component({
  selector:'app-merchants',
  standalone:true,
  imports:[CommonModule,FormsModule],
  template:`
  <section class="page-heading">
    <div>
      <div class="page-kicker">Merchant management</div>
      <h2>Merchant onboarding</h2>
      <p>Create merchant accounts, provision portal access and control activation readiness.</p>
    </div>
    <span class="muted">Admin controlled workflow</span>
  </section>
  <div class="grid onboarding-layout">
    <div class="card" *ngIf="!identityLoading && !identityError && me?.role==='SUPER_ADMIN'; else onboardingState">
      <div class="section-title">
        <div>
          <h2>Merchant onboarding</h2>
          <p class="muted">Create a PENDING merchant, provision the merchant portal administrator, then let the merchant register its Ed25519 public key from Merchant Portal.</p>
        </div>
        <span class="pill">Admin controlled</span>
      </div>

      <h3>1. Business profile</h3>
      <div class="grid merchant-form">
        <label>Merchant code<input class="input" [(ngModel)]="form.merchantCode" placeholder="ACME-MM-001"></label>
        <label>Merchant name<input class="input" [(ngModel)]="form.merchantName" placeholder="ACME Myanmar"></label>
        <label>Environment<select class="input" [(ngModel)]="form.environment"><option>UAT</option><option>PRODUCTION</option></select></label>
        <label>Contact email<input class="input" [(ngModel)]="form.contactEmail" placeholder="payments@example.com"></label>
      </div>

      <h3>2. Integration setup</h3>
      <p class="muted">The preferred flow is merchant self-service key generation. The optional public-key fields below are only for a merchant that already generated its key outside the portal (for example in an HSM/KMS).</p>
      <div class="grid merchant-form">
        <label>Return URL<input class="input" [(ngModel)]="form.redirectUrl" placeholder="https://merchant.example.com/payment/result"></label>
        <label>Webhook URL<input class="input" [(ngModel)]="form.webhookUrl" placeholder="https://merchant.example.com/webhooks/payment-status"></label>
        <label>Key ID (optional admin-assisted)<input class="input" [(ngModel)]="form.keyId" placeholder="merchant-prod-2026-01"></label>
        <label>Webhook description<input class="input" [(ngModel)]="form.webhookDescription" placeholder="Primary payment webhook"></label>
      </div>
      <label>Ed25519 public key (optional, admin-assisted only)<textarea class="input" rows="6" [(ngModel)]="form.publicKey" placeholder="-----BEGIN PUBLIC KEY-----"></textarea></label>

      <h3>3. Merchant portal administrator</h3>
      <p class="muted">The Gateway creates this user in Keycloak using a least-privilege service account. The temporary password is sent to Keycloak only; it is never stored in the Payment Gateway database. Keycloak's generated user ID is stored internally as the OIDC subject.</p>
      <label class="checkbox-row"><input type="checkbox" [(ngModel)]="portalAdminEnabled"> Create merchant portal administrator</label>

      <div *ngIf="portalAdminEnabled" class="advanced-block">
        <div class="grid merchant-form">
          <label>Email *<input class="input" [(ngModel)]="form.portalEmail" placeholder="admin@merchant.example"></label>
          <label>Username *<input class="input" [(ngModel)]="form.portalUsername" placeholder="merchant.admin"></label>
          <label>Display name *<input class="input" [(ngModel)]="form.portalDisplayName" placeholder="Merchant Administrator"></label>
        </div>
        <div class="grid merchant-form">
          <label>Temporary password *<input class="input" [type]="showTemporaryPassword ? 'text' : 'password'" [(ngModel)]="form.portalTemporaryPassword" autocomplete="new-password"></label>
          <label>Confirm temporary password *<input class="input" [type]="showTemporaryPassword ? 'text' : 'password'" [(ngModel)]="confirmTemporaryPassword" autocomplete="new-password"></label>
        </div>
        <div class="actions compact">
          <button class="btn secondary" type="button" (click)="generateTemporaryPassword()">Generate temporary password</button>
          <label class="checkbox-row"><input type="checkbox" [(ngModel)]="showTemporaryPassword"> Show password</label>
          <label class="checkbox-row"><input type="checkbox" [(ngModel)]="form.portalRequirePasswordChange"> Require password change on first login</label>
        </div>
        <p class="error" *ngIf="passwordMismatch">Temporary passwords do not match.</p>
      </div>

      <div class="actions">
        <button class="btn" [disabled]="saving || (portalAdminEnabled && !portalFormValid)" (click)="onboard()">{{saving ? 'Creating…' : 'Create onboarding'}}</button>
        <button class="btn secondary" type="button" (click)="reset()">Clear</button>
      </div>
      <p class="error" *ngIf="error">{{error}}</p>

      <div class="credential-panel" *ngIf="issuedTemporaryPassword">
        <h3>Merchant portal temporary password</h3>
        <p class="muted">Share this through an approved secure channel. It is shown from browser memory only and is not stored in the Gateway database.</p>
        <div class="credential-value mono">{{issuedTemporaryPassword}}</div>
        <div class="actions compact">
          <button class="btn secondary" type="button" (click)="copyIssuedPassword()">Copy temporary password</button>
          <button class="btn secondary" type="button" (click)="clearIssuedPassword()">Clear from screen</button>
        </div>
      </div>

      <div class="result-panel" *ngIf="created as c">
        <h3>Onboarding created</h3>
        <p><strong>{{c.merchantCode}}</strong> · {{c.merchantId}} · <span class="pill warning">{{c.status}}</span></p>
        <div class="grid readiness-grid" *ngIf="readiness as r">
          <div><span class="muted">Active signing keys</span><strong>{{r.activeSigningKeys}}</strong></div>
          <div><span class="muted">Redirect URLs</span><strong>{{r.activeRedirectUris}}</strong></div>
          <div><span class="muted">Webhooks</span><strong>{{r.activeWebhooks}}</strong></div>
          <div><span class="muted">Merchant admins</span><strong>{{r.activeMerchantAdmins}}</strong></div>
        </div>
        <p class="success" *ngIf="readiness?.readyForActivation">Ready for activation.</p>
        <p class="muted" *ngIf="readiness && !readiness.readyForActivation">Waiting for the merchant to register at least one ACTIVE Ed25519 public key in Merchant Portal.</p>
        <button class="btn" [disabled]="!readiness?.readyForActivation" (click)="activate(c.merchantId)">Activate merchant</button>
      </div>
    </div>

    <ng-template #onboardingState>
      <div class="card" *ngIf="identityLoading">
        <h3>Merchant onboarding</h3>
        <p class="muted">Loading your portal permissions…</p>
      </div>
      <div class="card" *ngIf="!identityLoading && identityError">
        <h3>Merchant onboarding</h3>
        <p class="error">{{identityError}}</p>
        <p class="muted">The portal could not load /api/v1/portal/me. This is a connectivity or authentication problem, not a read-only role.</p>
        <button class="btn secondary" type="button" (click)="loadIdentity()">Retry identity</button>
      </div>
      <div class="card" *ngIf="!identityLoading && !identityError && me?.role!=='SUPER_ADMIN'">
        <h3>Merchant onboarding</h3>
        <p class="muted">Your role ({{me?.role || 'UNKNOWN'}}) has read-only merchant access.</p>
      </div>
    </ng-template>

    <div class="card">
      <div class="section-title"><div><h3>Merchants</h3><p class="muted">PENDING merchants can sign in only to finish the allowed self-service key bootstrap; payment APIs remain blocked until activation.</p></div><button class="btn secondary" (click)="load()">Refresh</button></div>
      <div class="empty-state" *ngIf="listLoading">Loading merchants…</div>
      <div class="empty-state error" *ngIf="!listLoading && listError">{{listError}} <button class="btn secondary" type="button" (click)="load()">Retry</button></div>
      <div class="empty-state" *ngIf="!listLoading && !listError && !rows.length">No merchants found.</div>
      <div class="table-wrap" *ngIf="!listLoading && !listError && rows.length"><table>
        <tr><th>Code</th><th>Name</th><th>ID</th><th>Status</th><th>Environment</th><th>Actions</th></tr>
        <tr *ngFor="let m of rows">
          <td>{{m.merchant_code}}</td><td>{{m.merchant_name}}</td><td class="mono">{{m.merchant_id}}</td>
          <td><span class="pill" [class.warning]="m.status==='PENDING'">{{m.status}}</span></td><td>{{m.environment}}</td>
          <td class="actions compact" *ngIf="me?.role==='SUPER_ADMIN'; else noAction">
            <button class="btn secondary" (click)="review(m.merchant_id)">Readiness</button>
            <button class="btn secondary" (click)="openRedirectManager(m)">Return URLs</button>
            <button class="btn secondary" (click)="openPortalLoginRepair(m)">Repair login</button>
            <button class="btn" *ngIf="m.status!=='ACTIVE'" (click)="activate(m.merchant_id)">Activate</button>
            <button class="btn danger" *ngIf="m.status==='ACTIVE'" (click)="suspend(m.merchant_id)">Suspend</button>
          </td>
          <ng-template #noAction><td>—</td></ng-template>
        </tr>
      </table></div>
    </div>

    <div class="card" *ngIf="redirectMerchant as dm">
      <div class="section-title">
        <div>
          <h3>Manage merchant return URLs</h3>
          <p class="muted">Hosted checkout may redirect a customer only to an ACTIVE URL registered here. Matching is exact, including scheme, host, port, path and trailing slash.</p>
        </div>
        <button class="btn secondary" type="button" (click)="closeRedirectManager()">Close</button>
      </div>
      <p><strong>{{dm.merchant_code}}</strong> · {{dm.merchant_name}} · <span class="mono">{{dm.merchant_id}}</span></p>

      <div class="empty-state" *ngIf="redirectLoading">Loading return URLs…</div>
      <div class="empty-state error" *ngIf="!redirectLoading && redirectError">{{redirectError}} <button class="btn secondary" type="button" (click)="loadRedirects()">Retry</button></div>
      <div class="empty-state" *ngIf="!redirectLoading && !redirectError && !redirectRows.length">No return URLs are registered for this merchant.</div>
      <div class="table-wrap" *ngIf="!redirectLoading && !redirectError && redirectRows.length">
        <table>
          <tr><th>Return URL</th><th>Status</th><th>Updated</th><th>Action</th></tr>
          <tr *ngFor="let r of redirectRows">
            <td class="mono">{{r.redirect_uri}}</td>
            <td><span class="pill" [class.warning]="r.status!=='ACTIVE'">{{r.status}}</span></td>
            <td>{{r.updated_at || r.created_at}}</td>
            <td><button class="btn secondary" type="button" (click)="editRedirect(r)">Edit</button></td>
          </tr>
        </table>
      </div>

      <div class="advanced-block">
        <h3>{{redirectEditingId ? 'Edit return URL' : 'Add return URL'}}</h3>
        <div class="grid merchant-form">
          <label>Return URL *<input class="input" [(ngModel)]="redirectForm.redirectUrl" placeholder="http://localhost:9090/payment-result"></label>
          <label>Status
            <select class="input" [(ngModel)]="redirectForm.status">
              <option value="ACTIVE">ACTIVE</option>
              <option value="DISABLED">DISABLED</option>
            </select>
          </label>
        </div>
        <p class="muted">For the Spring Boot merchant test client, register <span class="mono">http://localhost:9090/payment-result</span> as ACTIVE.</p>
        <p class="error" *ngIf="redirectSaveError">{{redirectSaveError}}</p>
        <p class="success" *ngIf="redirectSavedMessage">{{redirectSavedMessage}}</p>
        <div class="actions compact">
          <button class="btn" type="button" [disabled]="redirectSaving || !redirectForm.redirectUrl.trim()" (click)="saveRedirect()">{{redirectSaving ? 'Saving…' : (redirectEditingId ? 'Save changes' : 'Add return URL')}}</button>
          <button class="btn secondary" type="button" (click)="newRedirect()">New URL</button>
          <button class="btn secondary" type="button" *ngIf="redirectEditingId" (click)="newRedirect()">Cancel edit</button>
        </div>
      </div>
    </div>

    <div class="card" *ngIf="repairMerchant as rm">
      <div class="section-title">
        <div>
          <h3>Repair merchant portal login</h3>
          <p class="muted">Use this when the merchant exists in PostgreSQL but its Keycloak login was lost or its password must be reset. The backend keeps the existing OIDC subject when it still exists; otherwise it creates a replacement Keycloak user and rebinds the local portal user.</p>
        </div>
        <button class="btn secondary" type="button" (click)="closePortalLoginRepair()">Close</button>
      </div>
      <p><strong>{{rm.merchant_code}}</strong> · {{rm.merchant_name}} · <span class="mono">{{rm.merchant_id}}</span></p>
      <div class="grid merchant-form">
        <label>Email *<input class="input" [(ngModel)]="repairForm.email" placeholder="admin@merchant.example"></label>
        <label>Username *<input class="input" [(ngModel)]="repairForm.username" placeholder="merchant.admin"></label>
        <label>Display name *<input class="input" [(ngModel)]="repairForm.displayName" placeholder="Merchant Administrator"></label>
      </div>
      <div class="grid merchant-form">
        <label>New temporary password *<input class="input" [type]="repairShowPassword ? 'text' : 'password'" [(ngModel)]="repairForm.temporaryPassword" autocomplete="new-password"></label>
        <label>Confirm temporary password *<input class="input" [type]="repairShowPassword ? 'text' : 'password'" [(ngModel)]="repairConfirmPassword" autocomplete="new-password"></label>
      </div>
      <div class="actions compact">
        <button class="btn secondary" type="button" (click)="generateRepairTemporaryPassword()">Generate temporary password</button>
        <label class="checkbox-row"><input type="checkbox" [(ngModel)]="repairShowPassword"> Show password</label>
        <label class="checkbox-row"><input type="checkbox" [(ngModel)]="repairForm.requirePasswordChange"> Require password change on first login</label>
      </div>
      <p class="error" *ngIf="repairPasswordMismatch">Temporary passwords do not match.</p>
      <p class="error" *ngIf="repairError">{{repairError}}</p>
      <div class="actions">
        <button class="btn" [disabled]="repairSaving || !repairFormValid" (click)="repairPortalLogin()">{{repairSaving ? 'Repairing…' : 'Repair portal login'}}</button>
        <button class="btn secondary" type="button" (click)="closePortalLoginRepair()">Cancel</button>
      </div>
      <div class="credential-panel" *ngIf="repairResult">
        <h3>Portal login repaired</h3>
        <p class="success" *ngIf="repairResult.action==='RESET_EXISTING_IDENTITY'">Existing Keycloak identity found. Profile and password were reset without changing its OIDC subject.</p>
        <p class="success" *ngIf="repairResult.action==='RECREATED_MISSING_IDENTITY'">Missing Keycloak identity was recreated and the local portal user was rebound to the new OIDC subject.</p>
        <p><span class="muted">Username</span><br><strong>{{repairResult.username}}</strong></p>
        <div *ngIf="repairIssuedTemporaryPassword">
          <p class="muted">Share the temporary password only through an approved secure channel. It exists only in this browser view and Keycloak; the Gateway does not persist it.</p>
          <div class="credential-value mono">{{repairIssuedTemporaryPassword}}</div>
          <div class="actions compact">
            <button class="btn secondary" type="button" (click)="copyRepairPassword()">Copy temporary password</button>
            <button class="btn secondary" type="button" (click)="clearRepairPassword()">Clear from screen</button>
          </div>
        </div>
      </div>
    </div>
  </div>`
})
export class MerchantsComponent implements OnInit {
  me?:PortalMe;
  rows:any[]=[];
  listLoading=true;
  listError='';
  created:any;
  readiness:any;
  error='';
  identityError='';
  identityLoading=true;
  saving=false;
  portalAdminEnabled=true;
  confirmTemporaryPassword='';
  showTemporaryPassword=false;
  issuedTemporaryPassword='';
  form:MerchantOnboardingRequest=this.emptyForm();
  repairMerchant:any;
  repairForm:MerchantPortalLoginRepairRequest=this.emptyRepairForm();
  repairConfirmPassword='';
  repairShowPassword=false;
  repairSaving=false;
  repairError='';
  repairResult:any;
  repairIssuedTemporaryPassword='';
  redirectMerchant:any;
  redirectRows:any[]=[];
  redirectLoading=false;
  redirectError='';
  redirectSaving=false;
  redirectSaveError='';
  redirectSavedMessage='';
  redirectEditingId='';
  redirectForm:{redirectUrl:string;status:'ACTIVE'|'DISABLED'}={redirectUrl:'',status:'ACTIVE'};

  constructor(private api:PortalApiService,private cdr:ChangeDetectorRef,private session:PortalSessionService){}
  ngOnInit(){this.applyCachedIdentity();this.load();}

  get passwordMismatch(){return !!this.form.portalTemporaryPassword && this.form.portalTemporaryPassword!==this.confirmTemporaryPassword;}
  get portalFormValid(){
    if(!this.portalAdminEnabled)return true;
    return !!this.form.portalEmail?.trim() && !!this.form.portalUsername?.trim() && !!this.form.portalDisplayName?.trim()
      && !!this.form.portalTemporaryPassword && this.form.portalTemporaryPassword.length>=12 && !this.passwordMismatch;
  }
  get repairPasswordMismatch(){return !!this.repairForm.temporaryPassword && this.repairForm.temporaryPassword!==this.repairConfirmPassword;}
  get repairFormValid(){
    return !!this.repairForm.email?.trim() && !!this.repairForm.username?.trim() && !!this.repairForm.displayName?.trim()
      && !!this.repairForm.temporaryPassword && this.repairForm.temporaryPassword.length>=12 && !this.repairPasswordMismatch;
  }

  private applyCachedIdentity(){
    this.me=this.session.current;
    this.identityLoading=false;
    this.identityError=this.session.lastError ? this.identityErrorMessage(this.session.lastError) : '';
    this.cdr.detectChanges();
  }
  async loadIdentity(){
    this.identityLoading=true;
    this.identityError='';
    this.cdr.detectChanges();
    try{this.me=await this.session.ensureLoaded(true);}
    catch(e){this.me=undefined;this.identityError=this.identityErrorMessage(e);}
    finally{this.identityLoading=false;this.cdr.detectChanges();}
  }
  private identityErrorMessage(e:any){
    const status=e?.status ? `HTTP ${e.status}` : 'request failed';
    return `Could not load portal identity (${status}).`;
  }
  load(){
    this.listLoading=true;
    this.listError='';
    this.api.merchants().subscribe({
      next:x=>{this.rows=x||[];this.listLoading=false;this.cdr.detectChanges();},
      error:e=>{
        this.listLoading=false;
        this.listError=e?.error?.message||`Could not load merchants${e?.status ? ` (HTTP ${e.status})` : ''}`;
        this.cdr.detectChanges();
      }
    });
  }

  onboard(){
    this.error='';
    if(this.portalAdminEnabled && !this.portalFormValid){this.error='Complete the merchant portal administrator fields and make sure the temporary passwords match.';return;}
    const temporaryPassword=this.portalAdminEnabled ? (this.form.portalTemporaryPassword||'') : '';
    this.saving=true;
    this.api.onboardMerchant(this.cleaned()).subscribe({
      next:r=>{
        this.created=r;
        this.saving=false;
        this.issuedTemporaryPassword=r.portalAdminConfigured ? temporaryPassword : '';
        this.form.portalTemporaryPassword='';
        this.confirmTemporaryPassword='';
        this.showTemporaryPassword=false;
        this.load();
        this.review(r.merchantId);
        this.cdr.detectChanges();
      },
      error:e=>{this.saving=false;this.error=this.describeOnboardingError(e);this.cdr.detectChanges();}
    });
  }
  review(id:string){this.api.merchantReadiness(id).subscribe({next:r=>{this.readiness=r;this.cdr.detectChanges();},error:e=>{this.error=e?.error?.message||'Could not load readiness';this.cdr.detectChanges();}});}
  activate(id:string){this.error='';this.api.activateMerchant(id).subscribe({next:r=>{this.readiness=r;this.load();this.cdr.detectChanges();},error:e=>{this.error=e?.error?.message||'Activation failed';this.cdr.detectChanges();}});}
  suspend(id:string){this.error='';this.api.suspendMerchant(id).subscribe({next:()=>{this.load();this.cdr.detectChanges();},error:e=>{this.error=e?.error?.message||'Suspend failed';this.cdr.detectChanges();}});}

  openRedirectManager(merchant:any){
    this.redirectMerchant=merchant;
    this.redirectRows=[];
    this.redirectError='';
    this.redirectSaveError='';
    this.redirectSavedMessage='';
    this.newRedirect();
    this.loadRedirects();
    this.cdr.detectChanges();
  }
  closeRedirectManager(){
    this.redirectMerchant=undefined;
    this.redirectRows=[];
    this.redirectLoading=false;
    this.redirectError='';
    this.redirectSaving=false;
    this.redirectSaveError='';
    this.redirectSavedMessage='';
    this.newRedirect();
    this.cdr.detectChanges();
  }
  loadRedirects(){
    if(!this.redirectMerchant)return;
    this.redirectLoading=true;
    this.redirectError='';
    this.api.merchantRedirects(this.redirectMerchant.merchant_id).subscribe({
      next:r=>{this.redirectRows=r||[];this.redirectLoading=false;this.cdr.detectChanges();},
      error:e=>{this.redirectLoading=false;this.redirectError=e?.error?.message||`Could not load return URLs${e?.status ? ` (HTTP ${e.status})` : ''}`;this.cdr.detectChanges();}
    });
  }
  newRedirect(){
    this.redirectEditingId='';
    this.redirectForm={redirectUrl:'',status:'ACTIVE'};
    this.redirectSaveError='';
    this.redirectSavedMessage='';
  }
  editRedirect(row:any){
    this.redirectEditingId=String(row.redirect_uri_id||'');
    this.redirectForm={redirectUrl:String(row.redirect_uri||''),status:String(row.status||'ACTIVE').toUpperCase()==='DISABLED'?'DISABLED':'ACTIVE'};
    this.redirectSaveError='';
    this.redirectSavedMessage='';
    this.cdr.detectChanges();
  }
  saveRedirect(){
    if(!this.redirectMerchant || !this.redirectForm.redirectUrl.trim())return;
    const merchantId=this.redirectMerchant.merchant_id;
    const redirectUrl=this.redirectForm.redirectUrl.trim();
    const status=this.redirectForm.status;
    this.redirectSaving=true;
    this.redirectSaveError='';
    this.redirectSavedMessage='';
    const request=this.redirectEditingId
      ? this.api.updateRedirect(merchantId,this.redirectEditingId,redirectUrl,status)
      : this.api.addRedirect(merchantId,redirectUrl);
    request.subscribe({
      next:()=>{
        this.redirectSaving=false;
        this.redirectSavedMessage=this.redirectEditingId?'Return URL updated.':'Return URL added.';
        this.redirectEditingId='';
        this.redirectForm={redirectUrl:'',status:'ACTIVE'};
        this.loadRedirects();
        this.review(merchantId);
        this.cdr.detectChanges();
      },
      error:e=>{
        this.redirectSaving=false;
        const statusText=e?.status ? `HTTP ${e.status}` : 'request failed';
        const detail=e?.error?.message||e?.error?.error||'';
        this.redirectSaveError=detail ? `Could not save return URL (${statusText}): ${detail}` : `Could not save return URL (${statusText}).`;
        this.cdr.detectChanges();
      }
    });
  }

  openPortalLoginRepair(merchant:any){
    this.repairMerchant=merchant;
    this.repairForm={
      username:merchant.portal_admin_username||'',
      email:merchant.contact_email||'',
      displayName:merchant.portal_admin_display_name||`${merchant.merchant_name||'Merchant'} Administrator`,
      temporaryPassword:'',
      requirePasswordChange:true
    };
    this.repairConfirmPassword='';
    this.repairShowPassword=false;
    this.repairSaving=false;
    this.repairError='';
    this.repairResult=undefined;
    this.repairIssuedTemporaryPassword='';
    this.cdr.detectChanges();
  }
  closePortalLoginRepair(){
    this.repairMerchant=undefined;
    this.repairForm=this.emptyRepairForm();
    this.repairConfirmPassword='';
    this.repairShowPassword=false;
    this.repairSaving=false;
    this.repairError='';
    this.repairResult=undefined;
    this.repairIssuedTemporaryPassword='';
    this.cdr.detectChanges();
  }
  generateRepairTemporaryPassword(){
    const password=this.newTemporaryPassword();
    this.repairForm.temporaryPassword=password;
    this.repairConfirmPassword=password;
    this.repairShowPassword=true;
  }
  repairPortalLogin(){
    if(!this.repairMerchant || !this.repairFormValid)return;
    this.repairError='';
    this.repairResult=undefined;
    this.repairSaving=true;
    const password=this.repairForm.temporaryPassword;
    const request:MerchantPortalLoginRepairRequest={...this.repairForm,username:this.repairForm.username.trim(),email:this.repairForm.email.trim(),displayName:this.repairForm.displayName.trim()};
    this.api.repairMerchantPortalLogin(this.repairMerchant.merchant_id,request).subscribe({
      next:r=>{
        this.repairSaving=false;
        this.repairResult=r;
        this.repairIssuedTemporaryPassword=password;
        this.repairForm.temporaryPassword='';
        this.repairConfirmPassword='';
        this.repairShowPassword=false;
        this.load();
        this.cdr.detectChanges();
      },
      error:e=>{
        this.repairSaving=false;
        const status=e?.status ? `HTTP ${e.status}` : 'request failed';
        const detail=e?.error?.message||e?.error?.error||'';
        this.repairError=detail ? `Portal login repair failed (${status}): ${detail}` : `Portal login repair failed (${status}). Check payment-api-service and Keycloak logs.`;
        this.cdr.detectChanges();
      }
    });
  }
  async copyRepairPassword(){if(this.repairIssuedTemporaryPassword)await navigator.clipboard.writeText(this.repairIssuedTemporaryPassword);}
  clearRepairPassword(){this.repairIssuedTemporaryPassword='';}

  generateTemporaryPassword(){
    const password=this.newTemporaryPassword();
    this.form.portalTemporaryPassword=password;
    this.confirmTemporaryPassword=password;
    this.showTemporaryPassword=true;
  }
  private newTemporaryPassword(){
    const alphabet='ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%*-_';
    const bytes=new Uint32Array(24);
    crypto.getRandomValues(bytes);
    return Array.from(bytes,b=>alphabet[b%alphabet.length]).join('');
  }
  async copyIssuedPassword(){if(this.issuedTemporaryPassword)await navigator.clipboard.writeText(this.issuedTemporaryPassword);}
  clearIssuedPassword(){this.issuedTemporaryPassword='';}
  reset(){this.form=this.emptyForm();this.created=undefined;this.readiness=undefined;this.error='';this.confirmTemporaryPassword='';this.showTemporaryPassword=false;this.issuedTemporaryPassword='';this.portalAdminEnabled=true;}

  private describeOnboardingError(e:any):string {
    const status=e?.status ? `HTTP ${e.status}` : 'request failed';
    const body=e?.error;
    const detail=typeof body==='string' ? body : (body?.message||body?.error||body?.detail||'');
    return detail ? `Merchant onboarding failed (${status}): ${detail}` : `Merchant onboarding failed (${status}). Check payment-api-service and Keycloak logs.`;
  }

  private cleaned():MerchantOnboardingRequest {
    const x:any={...this.form};
    if(!this.portalAdminEnabled){
      delete x.portalEmail; delete x.portalUsername; delete x.portalDisplayName;
      delete x.portalTemporaryPassword; delete x.portalRequirePasswordChange;
    }
    Object.keys(x).forEach(k=>{if(typeof x[k]==='string' && k!=='portalTemporaryPassword'){x[k]=x[k].trim();if(!x[k])delete x[k];}});
    return x;
  }
  private emptyForm():MerchantOnboardingRequest{return {merchantCode:'',merchantName:'',environment:'UAT',contactEmail:'',redirectUrl:'',keyId:'',publicKey:'',webhookUrl:'',webhookDescription:'',portalEmail:'',portalUsername:'',portalDisplayName:'',portalTemporaryPassword:'',portalRequirePasswordChange:true};}
  private emptyRepairForm():MerchantPortalLoginRepairRequest{return {username:'',email:'',displayName:'',temporaryPassword:'',requirePasswordChange:true};}
}
