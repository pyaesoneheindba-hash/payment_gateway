import {ChangeDetectorRef, Component, DestroyRef, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {auditTime, catchError, switchMap} from 'rxjs/operators';
import {merge, of, timer} from 'rxjs';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {PortalApiService,PortalMe} from '../../core/http/portal-api.service';
import {PortalSessionService} from '../../core/auth/portal-session.service';
import {LiveConnectionState, PortalLiveService} from '../../core/live/portal-live.service';

@Component({
  selector:'app-transactions',standalone:true,imports:[CommonModule,FormsModule],
  template:`
    <section class="page-heading">
      <div><div class="page-kicker">Operations</div><h2>Transactions</h2><p>Filter each visible payment column, review related webhook delivery state, and reconcile when permitted.</p></div>
      <div class="live-state" [class.degraded]="liveState!=='live'"><span class="live-dot"></span><span>{{liveLabel}}</span><span class="live-time" *ngIf="lastUpdated">{{total}} records · {{lastUpdated|date:'mediumTime'}}</span></div>
    </section>

    <section class="card table-card" style="margin-top:0">
      <div class="panel-title transaction-title-row">
        <div><h3>Current transactions</h3><div class="muted">Filters are directly below their matching columns · SSE live · 60s safety sync</div></div>
        <div class="transaction-toolbar">
          <label>Rows
            <select class="input compact-input" [(ngModel)]="pageSize" (change)="changePageSize()">
              <option [ngValue]="10">10</option><option [ngValue]="20">20</option><option [ngValue]="50">50</option><option [ngValue]="100">100</option>
            </select>
          </label>
          <button class="btn secondary" type="button" (click)="resetFilters()" [disabled]="!hasActiveFilters">Clear filters</button>
          <button class="btn secondary" type="button" (click)="load()" [disabled]="loading">Refresh now</button>
        </div>
      </div>

      <div *ngIf="filterError" class="transaction-error"><span>{{filterError}}</span><button class="text-button" type="button" (click)="filterError=''">Dismiss</button></div>
      <div *ngIf="error" class="transaction-error"><span>{{error}}</span><button class="text-button" type="button" (click)="load()">Retry</button></div>
      <div *ngIf="loading" class="transaction-loading">Loading transactions…</div>

      <div class="table-wrap transaction-table-wrap" *ngIf="!loading && (rows.length || hasActiveFilters); else empty">
        <table class="responsive-table transaction-table">
          <thead>
            <tr>
              <th>Merchant</th><th>Order</th><th>Payment ID</th><th>Amount</th><th>Provider</th><th>Payment status</th><th>Webhook</th><th>Created</th><th>Action</th>
            </tr>
            <tr class="column-filter-row">
              <th><input class="input column-filter-input" [(ngModel)]="merchantFilter" (keyup.enter)="applyFilters()" placeholder="Name, code, ID"></th>
              <th><input class="input column-filter-input" [(ngModel)]="orderFilter" (keyup.enter)="applyFilters()" placeholder="Order ID"></th>
              <th><input class="input column-filter-input mono" [(ngModel)]="paymentIdFilter" (keyup.enter)="applyFilters()" placeholder="Payment UUID"></th>
              <th>
                <div class="amount-filter">
                  <input class="input column-filter-input" type="number" min="0" [(ngModel)]="amountMinorFilter" (keyup.enter)="applyFilters()" placeholder="Minor amount">
                  <input class="input column-filter-input currency-filter" [(ngModel)]="currencyFilter" (keyup.enter)="applyFilters()" maxlength="3" placeholder="MMK">
                </div>
              </th>
              <th><input class="input column-filter-input" [(ngModel)]="providerFilter" (keyup.enter)="applyFilters()" placeholder="Provider"></th>
              <th>
                <select class="input column-filter-input" [(ngModel)]="statusFilter" (change)="applyFilters()">
                  <option value="">All</option><option value="PENDING">PENDING</option><option value="QR_GENERATED">QR_GENERATED</option><option value="ACTION_REQUIRED">ACTION_REQUIRED</option><option value="UNKNOWN">UNKNOWN</option><option value="SUCCEEDED">SUCCEEDED</option><option value="FAILED">FAILED</option><option value="EXPIRED">EXPIRED</option><option value="VOIDED">VOIDED</option>
                </select>
              </th>
              <th>
                <select class="input column-filter-input" [(ngModel)]="webhookFilter" (change)="applyFilters()">
                  <option value="">All</option><option value="ISSUES">Has issues</option><option value="RETRY">Retrying</option><option value="DEAD">Dead</option><option value="PENDING">Pending / sending</option><option value="DELIVERED">Has delivered</option><option value="NONE">No webhook</option>
                </select>
              </th>
              <th>
                <div class="date-filter"><input class="input column-filter-input" type="date" [(ngModel)]="createdFromFilter" title="Created from"><span>to</span><input class="input column-filter-input" type="date" [(ngModel)]="createdToFilter" title="Created to"></div>
              </th>
              <th><button class="btn column-apply-button" type="button" (click)="applyFilters()">Apply</button></th>
            </tr>
          </thead>
          <tbody>
            <ng-container *ngFor="let p of rows">
              <tr>
                <td data-label="Merchant"><div class="transaction-merchant"><strong>{{p.merchant_name || p.merchant_code || 'Merchant'}}</strong><span>{{p.merchant_code || '—'}}</span><small class="mono">{{p.merchant_id}}</small></div></td>
                <td data-label="Order"><strong>{{p.merchant_order_id}}</strong></td>
                <td data-label="Payment ID"><code class="mono payment-id-cell">{{p.payment_id}}</code></td>
                <td data-label="Amount">{{p.amount_minor}} {{p.currency}}</td>
                <td data-label="Provider">{{p.selected_provider_code||'—'}}</td>
                <td data-label="Status"><span class="pill" [class.warning]="isPending(p.status)" [class.danger]="isFailed(p.status)">{{p.status}}</span></td>
                <td data-label="Webhook"><div class="webhook-cell"><span class="pill" [class.warning]="hasWebhookRetry(p)" [class.danger]="hasWebhookDead(p)">{{webhookSummaryLabel(p)}}</span><button *ngIf="numberValue(p.webhook_total)>0" class="text-button" type="button" (click)="toggleWebhooks(p)">{{expandedPaymentId===p.payment_id ? 'Hide' : 'Details'}}</button></div></td>
                <td data-label="Created">{{p.created_at|date:'short'}}</td>
                <td data-label="Action"><button *ngIf="canReconcile" class="btn secondary" (click)="reconcile(p.merchant_id,p.payment_id)">Reconcile</button><span *ngIf="!canReconcile">—</span></td>
              </tr>
              <tr *ngIf="expandedPaymentId===p.payment_id" class="webhook-detail-row">
                <td colspan="9" data-label="Webhook details">
                  <div class="webhook-detail-panel">
                    <div class="webhook-detail-heading"><div><strong>Webhook deliveries for {{p.merchant_order_id}}</strong><div class="muted">Each row is a delivery for a payment status event, not another payment.</div></div><span class="mono">Payment {{p.payment_id}}</span></div>
                    <div *ngIf="webhookDetailLoading" class="muted">Loading webhook deliveries…</div>
                    <div *ngIf="webhookDetailError" class="error">{{webhookDetailError}}</div>
                    <div *ngIf="!webhookDetailLoading && !webhookDetailError && webhookDetails.length===0" class="muted">No webhook delivery rows exist for this payment.</div>
                    <div *ngFor="let w of webhookDetails" class="webhook-delivery-row">
                      <div><span>Payment event</span><strong>{{w.payment_previous_status || '—'}} → {{w.payment_event_status || '—'}}</strong><small>{{w.event_source || '—'}}</small></div>
                      <div><span>Delivery status</span><strong><span class="pill" [class.warning]="w.status==='RETRY'||w.status==='PENDING'||w.status==='SENDING'" [class.danger]="w.status==='DEAD'">{{w.status}}</span></strong></div>
                      <div><span>Attempts</span><strong>{{w.attempt_count || 0}}</strong></div><div><span>HTTP</span><strong>{{w.last_http_status || '—'}}</strong></div>
                      <div><span>Last attempt</span><strong>{{w.last_attempt_at ? (w.last_attempt_at | date:'short') : '—'}}</strong></div>
                      <div><span>Endpoint</span><strong class="mono webhook-url">{{w.endpoint_url || '—'}}</strong></div><div class="webhook-error-cell"><span>Last error</span><strong>{{w.last_error || '—'}}</strong></div>
                    </div>
                  </div>
                </td>
              </tr>
            </ng-container>
          </tbody>
        </table>
        <div *ngIf="!rows.length" class="empty-state">No transactions match the current column filters.</div>
      </div>
      <ng-template #empty><div *ngIf="!loading" class="empty-state">No transactions are available.</div></ng-template>

      <div class="transaction-pagination" *ngIf="!loading && total>0">
        <div class="pagination-summary">Showing <strong>{{showingFrom}}–{{showingTo}}</strong> of <strong>{{total}}</strong></div>
        <div class="pagination-actions"><button class="btn secondary" type="button" (click)="goToPage(0)" [disabled]="page===0">First</button><button class="btn secondary" type="button" (click)="goToPage(page-1)" [disabled]="page===0">Previous</button><span class="page-indicator">Page <strong>{{page+1}}</strong> of <strong>{{totalPages || 1}}</strong></span><button class="btn secondary" type="button" (click)="goToPage(page+1)" [disabled]="page+1>=totalPages">Next</button><button class="btn secondary" type="button" (click)="goToPage(totalPages-1)" [disabled]="page+1>=totalPages">Last</button></div>
      </div>
    </section>`
})
export class TransactionsComponent implements OnInit{
  readonly safetySyncSeconds=60;
  rows:any[]=[];me?:PortalMe;lastUpdated?:Date;liveState:LiveConnectionState='connecting';loading=true;error='';filterError='';
  merchantFilter='';orderFilter='';paymentIdFilter='';amountMinorFilter:number|null=null;currencyFilter='';providerFilter='';statusFilter='';webhookFilter='';createdFromFilter='';createdToFilter='';
  page=0;pageSize=20;total=0;totalPages=0;expandedPaymentId?:string;webhookDetails:any[]=[];webhookDetailLoading=false;webhookDetailError='';

  get canReconcile(){return this.me?.role==='SUPER_ADMIN'||this.me?.role==='OPERATIONS';}
  get liveLabel(){return this.liveState==='live'?'Live':this.liveState==='reconnecting'?'Reconnecting…':this.liveState==='connecting'?'Connecting…':'Offline';}
  get showingFrom(){return this.total===0?0:this.page*this.pageSize+1;}
  get showingTo(){return Math.min(this.total,(this.page+1)*this.pageSize);}
  get hasActiveFilters(){return !!(this.merchantFilter.trim()||this.orderFilter.trim()||this.paymentIdFilter.trim()||this.amountMinorFilter!==null||this.currencyFilter.trim()||this.providerFilter.trim()||this.statusFilter||this.webhookFilter||this.createdFromFilter||this.createdToFilter);}

  constructor(private api:PortalApiService,private live:PortalLiveService,private cdr:ChangeDetectorRef,private destroyRef:DestroyRef,private session:PortalSessionService){}

  ngOnInit(){
    this.live.acquire();this.destroyRef.onDestroy(()=>this.live.release());
    this.live.state$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(state=>{this.liveState=state;this.cdr.detectChanges();});
    this.me=this.session.current;
    merge(of(null),timer(this.safetySyncSeconds*1000,this.safetySyncSeconds*1000),this.live.events$.pipe(auditTime(120)))
      .pipe(switchMap(()=>this.api.transactionsPage(this.query()).pipe(catchError(()=>of(null)))),takeUntilDestroyed(this.destroyRef))
      .subscribe(x=>{if(x!==null){this.applyPageResponse(x);this.error='';}else if(this.loading){this.error='Could not load transactions.';}this.loading=false;this.cdr.detectChanges();});
  }

  query(){return {page:this.page,size:this.pageSize,merchant:this.merchantFilter,orderId:this.orderFilter,paymentId:this.paymentIdFilter,amountMinor:this.amountMinorFilter,currency:this.currencyFilter,provider:this.providerFilter,status:this.statusFilter,webhook:this.webhookFilter,createdFrom:this.createdFromFilter,createdTo:this.createdToFilter};}
  applyPageResponse(x:any){this.rows=x?.items||[];this.page=Number(x?.page||0);this.pageSize=Number(x?.size||this.pageSize);this.total=Number(x?.total||0);this.totalPages=Number(x?.totalPages||0);this.lastUpdated=new Date();if(this.expandedPaymentId&&!this.rows.some(r=>r.payment_id===this.expandedPaymentId))this.closeWebhookDetails();}
  load(){this.loading=true;this.error='';this.cdr.detectChanges();this.api.transactionsPage(this.query()).subscribe({next:x=>{this.applyPageResponse(x);this.loading=false;this.cdr.detectChanges();},error:()=>{this.error='Could not load transactions.';this.loading=false;this.cdr.detectChanges();}});}
  applyFilters(){
    this.filterError='';
    if(this.createdFromFilter&&this.createdToFilter&&this.createdFromFilter>this.createdToFilter){this.filterError='Created From date cannot be later than Created To date.';this.cdr.detectChanges();return;}
    if(this.amountMinorFilter!==null&&Number(this.amountMinorFilter)<0){this.filterError='Amount must be zero or greater.';this.cdr.detectChanges();return;}
    this.currencyFilter=this.currencyFilter.trim().toUpperCase();this.page=0;this.closeWebhookDetails();this.load();
  }
  resetFilters(){this.merchantFilter='';this.orderFilter='';this.paymentIdFilter='';this.amountMinorFilter=null;this.currencyFilter='';this.providerFilter='';this.statusFilter='';this.webhookFilter='';this.createdFromFilter='';this.createdToFilter='';this.filterError='';this.page=0;this.closeWebhookDetails();this.load();}
  changePageSize(){this.page=0;this.closeWebhookDetails();this.load();}
  goToPage(next:number){if(next<0||next>=this.totalPages||next===this.page)return;this.page=next;this.closeWebhookDetails();this.load();}
  reconcile(mid:string,pid:string){this.api.reconcile(mid,pid).subscribe({next:()=>this.load(),error:()=>this.cdr.detectChanges()});}

  toggleWebhooks(p:any){if(this.expandedPaymentId===p.payment_id){this.closeWebhookDetails();this.cdr.detectChanges();return;}this.expandedPaymentId=p.payment_id;this.webhookDetails=[];this.webhookDetailError='';this.webhookDetailLoading=true;this.cdr.detectChanges();this.api.paymentWebhooks(p.payment_id).subscribe({next:x=>{if(this.expandedPaymentId===p.payment_id){this.webhookDetails=x||[];this.webhookDetailLoading=false;this.cdr.detectChanges();}},error:()=>{if(this.expandedPaymentId===p.payment_id){this.webhookDetailError='Could not load webhook delivery details.';this.webhookDetailLoading=false;this.cdr.detectChanges();}}});}
  closeWebhookDetails(){this.expandedPaymentId=undefined;this.webhookDetails=[];this.webhookDetailLoading=false;this.webhookDetailError='';}
  numberValue(v:any){const n=Number(v);return Number.isFinite(n)?n:0;}
  hasWebhookRetry(p:any){return this.numberValue(p.webhook_retrying)>0;}hasWebhookDead(p:any){return this.numberValue(p.webhook_dead)>0;}
  webhookSummaryLabel(p:any){const total=this.numberValue(p.webhook_total),delivered=this.numberValue(p.webhook_delivered),pending=this.numberValue(p.webhook_pending),retry=this.numberValue(p.webhook_retrying),dead=this.numberValue(p.webhook_dead);if(total===0)return'None';if(dead>0)return`${dead} dead · ${total} total`;if(retry>0)return`${retry} retrying · ${total} total`;if(pending>0)return`${pending} pending · ${total} total`;if(delivered===total)return`${delivered} delivered`;return`${delivered}/${total} delivered`;}
  isPending(status:any){return['PENDING','QR_GENERATED','ACTION_REQUIRED','UNKNOWN'].includes(String(status||'').toUpperCase());}isFailed(status:any){return['FAILED','EXPIRED','VOIDED'].includes(String(status||'').toUpperCase());}
}
