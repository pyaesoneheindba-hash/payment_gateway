import {ChangeDetectorRef, Component, DestroyRef, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {auditTime, catchError, switchMap} from 'rxjs/operators';
import {forkJoin, merge, of, timer} from 'rxjs';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {PortalApiService} from '../../core/http/portal-api.service';
import {LiveConnectionState, PortalLiveEvent, PortalLiveService} from '../../core/live/portal-live.service';

@Component({
  selector:'app-dashboard',
  standalone:true,
  imports:[CommonModule],
  template:`
    <section class="page-heading">
      <div>
        <div class="page-kicker">Operations overview</div>
        <h2>Dashboard</h2>
        <p>Monitor merchant activity, payment health and recent gateway transactions.</p>
      </div>
      <div class="live-state" [class.degraded]="liveState!=='live'">
        <span class="live-dot"></span>
        <span>{{liveLabel}}</span>
        <span class="live-time" *ngIf="lastLiveEvent">Last event {{lastLiveEvent.type}} · {{shortId(lastLiveEvent.paymentId || '')}} · {{lastLiveEventAt | date:'mediumTime'}}</span>
        <span class="live-time" *ngIf="liveState!=='live' && liveError">{{liveError}}</span>
        <span class="live-time" *ngIf="!lastLiveEvent && lastUpdated && !liveError">No live event yet · Synced {{lastUpdated | date:'mediumTime'}}</span>
      </div>
    </section>

    <section class="grid grid-5">
      <div class="card metric-card">
        <div class="metric-label">Merchants</div>
        <div class="metric-row"><div class="metric">{{s.merchants || 0}}</div><span class="metric-delta">Portfolio</span></div>
      </div>
      <div class="card metric-card">
        <div class="metric-label">Total payments</div>
        <div class="metric-row"><div class="metric">{{s.payments || 0}}</div><span class="metric-delta">All time</span></div>
      </div>
      <div class="card metric-card">
        <div class="metric-label">Succeeded</div>
        <div class="metric-row"><div class="metric">{{s.succeeded || 0}}</div><span class="metric-delta">{{successRate}}%</span></div>
      </div>
      <div class="card metric-card">
        <div class="metric-label">In progress</div>
        <div class="metric-row"><div class="metric">{{s.pending || 0}}</div><span class="metric-delta">Pending / QR / Unknown</span></div>
      </div>
      <div class="card metric-card">
        <div class="metric-label">Failed / expired</div>
        <div class="metric-row"><div class="metric">{{s.failedOrExpired || 0}}</div><span class="metric-delta" [class.down]="(s.failedOrExpired || 0) > 0">{{(s.failedOrExpired || 0) > 0 ? 'Needs review' : 'Healthy'}}</span></div>
      </div>
    </section>

    <section class="dashboard-panels payment-activity-panels">
      <div class="card activity-card">
        <div class="panel-title">
          <div><h3>Payment activity</h3><span class="muted">Hourly payments created during the last {{activity.windowHours || 24}} hours</span></div>
          <strong class="activity-total">{{activityWindowTotal}} payments</strong>
        </div>
        <div class="activity-legend">
          <span><i class="activity-key success"></i>Succeeded</span>
          <span><i class="activity-key pending"></i>In progress</span>
          <span><i class="activity-key failed"></i>Failed / expired</span>
          <span><i class="activity-key other"></i>Other</span>
        </div>
        <div class="hourly-chart" *ngIf="activityHourly.length; else noHourlyActivity">
          <div class="hour-column" *ngFor="let h of activityHourly; let i=index" [title]="hourTooltip(h)">
            <div class="hour-value" *ngIf="numberValue(h.total)>0">{{h.total}}</div>
            <div class="hour-bar-shell">
              <div class="hour-bar" [style.height.%]="activityBarHeight(h.total)">
                <span class="bar-segment failed" [style.height.%]="statusShare(h.failed_or_expired,h.total)"></span>
                <span class="bar-segment other" [style.height.%]="statusShare(h.other,h.total)"></span>
                <span class="bar-segment pending" [style.height.%]="statusShare(h.in_progress,h.total)"></span>
                <span class="bar-segment success" [style.height.%]="statusShare(h.succeeded,h.total)"></span>
              </div>
            </div>
            <span class="hour-label">{{showHourLabel(i) ? (h.bucket | date:'ha') : ''}}</span>
          </div>
        </div>
        <ng-template #noHourlyActivity><div class="empty-chart-state">No payment activity in this time window.</div></ng-template>
      </div>

      <div class="card activity-card">
        <div class="panel-title">
          <div><h3>Provider activity</h3><span class="muted">Payment traffic by provider during the last {{activity.windowHours || 24}} hours</span></div>
          <strong class="activity-total">{{activityProviders.length}} active</strong>
        </div>
        <div class="provider-activity" *ngIf="activityProviders.length; else noProviderActivity">
          <div class="provider-row" *ngFor="let p of activityProviders">
            <div class="provider-row-head"><strong>{{p.provider}}</strong><span>{{p.total}} payments</span></div>
            <div class="provider-track" [title]="providerTooltip(p)">
              <div class="provider-stack" [style.width.%]="providerBarWidth(p.total)">
                <span class="provider-segment success" [style.width.%]="statusShare(p.succeeded,p.total)"></span>
                <span class="provider-segment pending" [style.width.%]="statusShare(p.in_progress,p.total)"></span>
                <span class="provider-segment failed" [style.width.%]="statusShare(p.failed_or_expired,p.total)"></span>
                <span class="provider-segment other" [style.width.%]="statusShare(p.other,p.total)"></span>
              </div>
            </div>
          </div>
        </div>
        <ng-template #noProviderActivity><div class="empty-chart-state">No provider activity in this time window.</div></ng-template>
      </div>
    </section>

    <section class="card webhook-health-card">
      <div class="panel-title">
        <h3>Webhook delivery health</h3>
        <span class="muted">Delivery records are tracked separately from payment counts</span>
      </div>
      <div class="webhook-health-grid">
        <div class="webhook-health-item"><span>Total deliveries</span><strong>{{s.webhookTotal || 0}}</strong></div>
        <div class="webhook-health-item"><span>Delivered</span><strong class="success">{{s.webhookDelivered || 0}}</strong></div>
        <div class="webhook-health-item"><span>Pending / sending</span><strong>{{s.webhookPending || 0}}</strong></div>
        <div class="webhook-health-item"><span>Retrying</span><strong class="warning-text">{{s.webhookRetrying || 0}}</strong></div>
        <div class="webhook-health-item"><span>Dead</span><strong class="error">{{s.webhookDead || 0}}</strong></div>
        <div class="webhook-health-item"><span>Affected payments</span><strong>{{s.paymentsWithWebhookIssues || 0}}</strong></div>
      </div>
    </section>

    <section class="card table-card">
      <div class="panel-title">
        <h3>Recent transactions</h3>
        <span class="muted">Payment status and related webhook delivery state · Live SSE updates · 60s safety sync</span>
      </div>
      <div class="table-wrap" *ngIf="recent.length; else noTransactions">
        <table class="responsive-table">
          <tbody>
            <tr><th>Merchant</th><th>Order</th><th>Amount</th><th>Provider</th><th>Payment status</th><th>Webhook</th><th>Created</th></tr>
            <ng-container *ngFor="let p of recent">
              <tr>
                <td class="mono" data-label="Merchant">{{shortId(p.merchant_id)}}</td>
                <td data-label="Order">{{p.merchant_order_id}}</td>
                <td data-label="Amount">{{p.amount_minor}} {{p.currency}}</td>
                <td data-label="Provider">{{p.selected_provider_code || '—'}}</td>
                <td data-label="Payment status"><span class="pill" [class.warning]="isPending(p.status)" [class.danger]="isFailed(p.status)">{{p.status}}</span></td>
                <td data-label="Webhook">
                  <div class="webhook-cell">
                    <span class="pill" [class.warning]="hasWebhookRetry(p)" [class.danger]="hasWebhookDead(p)">{{webhookSummaryLabel(p)}}</span>
                    <button *ngIf="numberValue(p.webhook_total)>0" class="text-button" type="button" (click)="toggleWebhooks(p)">{{expandedPaymentId===p.payment_id ? 'Hide' : 'Details'}}</button>
                  </div>
                </td>
                <td data-label="Created">{{p.created_at | date:'short'}}</td>
              </tr>
              <tr *ngIf="expandedPaymentId===p.payment_id" class="webhook-detail-row">
                <td colspan="7" data-label="Webhook details">
                  <div class="webhook-detail-panel">
                    <div class="webhook-detail-heading">
                      <strong>Webhook deliveries for {{p.merchant_order_id}}</strong>
                      <span class="mono">Payment {{p.payment_id}}</span>
                    </div>
                    <div *ngIf="webhookDetailLoading" class="muted">Loading webhook deliveries…</div>
                    <div *ngIf="webhookDetailError" class="error">{{webhookDetailError}}</div>
                    <div *ngIf="!webhookDetailLoading && !webhookDetailError && webhookDetails.length===0" class="muted">No webhook delivery rows exist for this payment.</div>
                    <div *ngFor="let w of webhookDetails" class="webhook-delivery-row">
                      <div><span>Payment event</span><strong>{{w.payment_previous_status || '—'}} → {{w.payment_event_status || '—'}}</strong><small>{{w.event_source || '—'}}</small></div>
                      <div><span>Delivery status</span><strong><span class="pill" [class.warning]="w.status==='RETRY'||w.status==='PENDING'||w.status==='SENDING'" [class.danger]="w.status==='DEAD'">{{w.status}}</span></strong></div>
                      <div><span>Attempts</span><strong>{{w.attempt_count || 0}}</strong></div>
                      <div><span>HTTP</span><strong>{{w.last_http_status || '—'}}</strong></div>
                      <div><span>Last attempt</span><strong>{{w.last_attempt_at ? (w.last_attempt_at | date:'short') : '—'}}</strong></div>
                      <div><span>Endpoint</span><strong class="mono webhook-url">{{w.endpoint_url || '—'}}</strong></div>
                      <div class="webhook-error-cell"><span>Last error</span><strong>{{w.last_error || '—'}}</strong></div>
                    </div>
                  </div>
                </td>
              </tr>
            </ng-container>
          </tbody>
        </table>
      </div>
      <ng-template #noTransactions><div class="empty-state">No transactions are available yet.</div></ng-template>
    </section>
  `
})
export class DashboardComponent implements OnInit {
  readonly safetySyncSeconds=60;
  s:any={};
  recent:any[]=[];
  activity:any={windowHours:24,hourly:[],providers:[]};
  lastUpdated?:Date;
  liveState:LiveConnectionState='connecting';
  lastLiveEvent?:PortalLiveEvent;
  lastLiveEventAt?:Date;
  liveError='';
  expandedPaymentId?:string;
  webhookDetails:any[]=[];
  webhookDetailLoading=false;
  webhookDetailError='';

  constructor(
    private api:PortalApiService,
    private live:PortalLiveService,
    private cdr:ChangeDetectorRef,
    private destroyRef:DestroyRef
  ){}

  ngOnInit(){
    this.live.acquire();
    this.destroyRef.onDestroy(()=>this.live.release());
    this.live.state$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(state=>{
      this.liveState=state;
      this.cdr.detectChanges();
    });
    this.live.error$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(error=>{
      this.liveError=error;
      this.cdr.detectChanges();
    });
    this.live.events$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(event=>{
      this.lastLiveEvent=event;
      this.lastLiveEventAt=new Date();
      this.cdr.detectChanges();
    });

    merge(
      of(null),
      timer(this.safetySyncSeconds*1000,this.safetySyncSeconds*1000),
      this.live.events$.pipe(auditTime(120))
    ).pipe(
      switchMap(()=>forkJoin({
        summary:this.api.summary().pipe(catchError(()=>of(null))),
        transactions:this.api.transactions().pipe(catchError(()=>of(null))),
        activity:this.api.dashboardActivity(24).pipe(catchError(()=>of(null)))
      })),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(({summary,transactions,activity})=>{
      if(summary!==null)this.s=summary||{};
      if(transactions!==null){
        const rows=transactions||[];
        this.recent=rows.slice(0,8);
      }
      if(activity!==null)this.activity=activity||{windowHours:24,hourly:[],providers:[]};
      if(summary!==null || transactions!==null || activity!==null)this.lastUpdated=new Date();
      this.cdr.detectChanges();
    });
  }

  get liveLabel(){return this.liveState==='live'?'Live':this.liveState==='reconnecting'?'Reconnecting…':this.liveState==='connecting'?'Connecting…':'Offline';}
  get successRate(){const total=Number(this.s.payments||0);return total ? Math.round(Number(this.s.succeeded||0)*100/total) : 0;}
  get activityHourly(){return Array.isArray(this.activity?.hourly)?this.activity.hourly:[];}
  get activityProviders(){return Array.isArray(this.activity?.providers)?this.activity.providers:[];}
  get activityWindowTotal(){return this.activityHourly.reduce((sum:any,row:any)=>sum+this.numberValue(row.total),0);}
  get activityMaxTotal(){return Math.max(1,...this.activityHourly.map((row:any)=>this.numberValue(row.total)));}
  get providerMaxTotal(){return Math.max(1,...this.activityProviders.map((row:any)=>this.numberValue(row.total)));}

  activityBarHeight(total:any){
    const value=this.numberValue(total);
    return value===0?0:Math.max(8,Math.round(value*100/this.activityMaxTotal));
  }
  providerBarWidth(total:any){
    const value=this.numberValue(total);
    return value===0?0:Math.max(8,Math.round(value*100/this.providerMaxTotal));
  }
  statusShare(value:any,total:any){
    const denominator=this.numberValue(total);
    return denominator?this.numberValue(value)*100/denominator:0;
  }
  showHourLabel(index:number){
    const count=this.activityHourly.length;
    if(count<=8)return true;
    return index===0 || index===count-1 || index%4===0;
  }
  hourTooltip(h:any){
    return `${new Date(h.bucket).toLocaleString()} · ${this.numberValue(h.total)} payments · ${this.numberValue(h.succeeded)} succeeded · ${this.numberValue(h.in_progress)} in progress · ${this.numberValue(h.failed_or_expired)} failed/expired`;
  }
  providerTooltip(p:any){
    return `${p.provider}: ${this.numberValue(p.total)} payments · ${this.numberValue(p.succeeded)} succeeded · ${this.numberValue(p.in_progress)} in progress · ${this.numberValue(p.failed_or_expired)} failed/expired`;
  }

  shortId(v:string){return v ? `${v.slice(0,8)}…` : '—';}
  numberValue(v:any){return Number(v||0);}
  isPending(v:string){return ['PENDING','CREATED','QR_GENERATED','PROCESSING','ACTION_REQUIRED','UNKNOWN'].includes(String(v||'').toUpperCase());}
  isFailed(v:string){return ['FAILED','EXPIRED','CANCELLED','VOIDED'].includes(String(v||'').toUpperCase());}
  hasWebhookRetry(p:any){return this.numberValue(p.webhook_retrying)>0 || this.numberValue(p.webhook_pending)>0;}
  hasWebhookDead(p:any){return this.numberValue(p.webhook_dead)>0;}

  webhookSummaryLabel(p:any){
    const total=this.numberValue(p.webhook_total);
    const delivered=this.numberValue(p.webhook_delivered);
    const pending=this.numberValue(p.webhook_pending);
    const retry=this.numberValue(p.webhook_retrying);
    const dead=this.numberValue(p.webhook_dead);
    if(total===0)return 'No delivery';
    if(dead>0)return `${dead} dead · ${total} total`;
    if(retry>0)return `${retry} retrying · ${total} total`;
    if(pending>0)return `${pending} pending · ${total} total`;
    if(delivered===total)return `${delivered} delivered`;
    return `${delivered}/${total} delivered`;
  }

  toggleWebhooks(p:any){
    if(this.expandedPaymentId===p.payment_id){
      this.expandedPaymentId=undefined;
      this.webhookDetails=[];
      this.webhookDetailError='';
      return;
    }
    this.expandedPaymentId=p.payment_id;
    this.webhookDetails=[];
    this.webhookDetailError='';
    this.webhookDetailLoading=true;
    this.api.paymentWebhooks(p.payment_id).subscribe({
      next:rows=>{
        if(this.expandedPaymentId!==p.payment_id)return;
        this.webhookDetails=rows||[];
        this.webhookDetailLoading=false;
        this.cdr.detectChanges();
      },
      error:e=>{
        if(this.expandedPaymentId!==p.payment_id)return;
        const detail=e?.error?.message||e?.error?.error||'';
        this.webhookDetailError=detail ? `Could not load webhook deliveries: ${detail}` : 'Could not load webhook deliveries.';
        this.webhookDetailLoading=false;
        this.cdr.detectChanges();
      }
    });
  }

}
