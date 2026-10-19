import {ChangeDetectorRef, Component, DestroyRef, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {auditTime, catchError, switchMap} from 'rxjs/operators';
import {forkJoin, merge, of, timer} from 'rxjs';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {PortalApiService} from '../../core/http/portal-api.service';
import {LiveConnectionState, PortalLiveService} from '../../core/live/portal-live.service';

@Component({
  selector:'app-dashboard',
  standalone:true,
  imports:[CommonModule],
  template:`
    <section class="page-heading">
      <div>
        <div class="page-kicker">Merchant overview</div>
        <h2>Dashboard</h2>
        <p>Track payment activity, success rate and the latest customer transactions.</p>
      </div>
      <div class="live-state" [class.degraded]="liveState!=='live'">
        <span class="live-dot"></span>
        <span>{{liveLabel}}</span>
        <span class="live-time" *ngIf="lastUpdated">Synced {{lastUpdated | date:'mediumTime'}}</span>
      </div>
    </section>

    <section class="grid grid-4">
      <div class="card metric-card"><div class="metric-label">Transactions</div><div class="metric-row"><div class="metric">{{s.total_transactions||0}}</div><span class="metric-delta">Total</span></div></div>
      <div class="card metric-card"><div class="metric-label">Succeeded</div><div class="metric-row"><div class="metric">{{s.succeeded||0}}</div><span class="metric-delta">{{successRate}}%</span></div></div>
      <div class="card metric-card"><div class="metric-label">Pending</div><div class="metric-row"><div class="metric">{{s.pending||0}}</div><span class="metric-delta">In progress</span></div></div>
      <div class="card metric-card"><div class="metric-label">Failed / Expired</div><div class="metric-row"><div class="metric">{{s.failed_or_expired||0}}</div><span class="metric-delta" [class.down]="(s.failed_or_expired||0)>0">{{(s.failed_or_expired||0)>0?'Review':'Healthy'}}</span></div></div>
    </section>

    <section class="dashboard-panels">
      <div class="card chart-card">
        <div class="panel-title"><h3>Transaction activity</h3><span class="muted">Latest {{trendCount}} transactions</span></div>
        <div class="trend-chart"><svg viewBox="0 0 100 100" preserveAspectRatio="none"><polyline [attr.points]="trendPoints"></polyline></svg></div>
        <div class="chart-axis"><span>Older</span><span>Recent</span></div>
      </div>

      <div class="card">
        <div class="panel-title"><h3>Payment status</h3><span class="muted">Recent sample</span></div>
        <div class="status-panel">
          <div class="donut" [style.background]="donutBackground"><div class="donut-center"><strong>{{statusTotal}}</strong><span>payments</span></div></div>
          <div class="legend">
            <div class="legend-row"><span class="legend-dot success"></span><span>Succeeded</span><strong>{{statusSucceeded}}</strong></div>
            <div class="legend-row"><span class="legend-dot pending"></span><span>Pending</span><strong>{{statusPending}}</strong></div>
            <div class="legend-row"><span class="legend-dot failed"></span><span>Failed / expired</span><strong>{{statusFailed}}</strong></div>
            <div class="legend-row"><span class="legend-dot other"></span><span>Other</span><strong>{{statusOther}}</strong></div>
          </div>
        </div>
      </div>
    </section>

    <section class="card table-card">
      <div class="panel-title"><h3>Recent transactions</h3><span class="muted">Live SSE updates · 60s safety sync</span></div>
      <div class="table-wrap" *ngIf="recent.length; else noTransactions">
        <table class="responsive-table">
          <tr><th>Order</th><th>Amount</th><th>Provider</th><th>Status</th><th>Created</th></tr>
          <tr *ngFor="let p of recent"><td data-label="Order">{{p.merchant_order_id}}</td><td data-label="Amount">{{p.amount_minor}} {{p.currency}}</td><td data-label="Provider">{{p.selected_provider_code||'—'}}</td><td data-label="Status"><span class="pill">{{p.status}}</span></td><td data-label="Created">{{p.created_at|date:'short'}}</td></tr>
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
  trendPoints='0,86 14,82 28,74 42,80 56,60 70,66 84,48 100,54';
  trendCount=0;
  statusSucceeded=0;
  statusPending=0;
  statusFailed=0;
  statusOther=0;
  lastUpdated?:Date;
  liveState:LiveConnectionState='connecting';

  constructor(
    private api:PortalApiService,
    private live:PortalLiveService,
    private cdr:ChangeDetectorRef,
    private destroyRef:DestroyRef
  ){}

  ngOnInit(){
    this.live.acquire();
    this.destroyRef.onDestroy(()=>this.live.release());
    this.live.state$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(state=>{this.liveState=state;this.cdr.detectChanges();});
    merge(
      of(null),
      timer(this.safetySyncSeconds*1000,this.safetySyncSeconds*1000),
      this.live.events$.pipe(auditTime(120))
    ).pipe(
      switchMap(()=>forkJoin({
        summary:this.api.summary().pipe(catchError(()=>of(null))),
        transactions:this.api.transactions().pipe(catchError(()=>of(null)))
      })),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(({summary,transactions})=>{
      if(summary!==null)this.s=summary||{};
      if(transactions!==null){
        const rows=transactions||[];
        this.recent=rows.slice(0,8);
        this.buildVisuals(rows);
      }
      if(summary!==null || transactions!==null)this.lastUpdated=new Date();
      this.cdr.detectChanges();
    });
  }

  get liveLabel(){return this.liveState==='live'?'Live':this.liveState==='reconnecting'?'Reconnecting…':this.liveState==='connecting'?'Connecting…':'Offline';}
  get successRate(){const t=Number(this.s.total_transactions||0);return t?Math.round(Number(this.s.succeeded||0)*100/t):0;}
  get statusTotal(){return this.statusSucceeded+this.statusPending+this.statusFailed+this.statusOther;}
  get donutBackground(){const t=this.statusTotal||1;const a=this.statusSucceeded*100/t;const b=a+this.statusPending*100/t;const c=b+this.statusFailed*100/t;return `conic-gradient(#5169d8 0 ${a}%, #36c88c ${a}% ${b}%, #ef6f78 ${b}% ${c}%, #f0c84b ${c}% 100%)`;}

  private buildVisuals(rows:any[]){
    const sample=(rows||[]).slice(0,12);
    const pending=['PENDING','CREATED','QR_GENERATED','PROCESSING','ACTION_REQUIRED','UNKNOWN'];
    this.statusSucceeded=sample.filter(r=>['SUCCESS','SUCCEEDED','PAID','COMPLETED'].includes(String(r.status||'').toUpperCase())).length;
    this.statusPending=sample.filter(r=>pending.includes(String(r.status||'').toUpperCase())).length;
    this.statusFailed=sample.filter(r=>['FAILED','EXPIRED','CANCELLED','VOIDED'].includes(String(r.status||'').toUpperCase())).length;
    this.statusOther=Math.max(0,sample.length-this.statusSucceeded-this.statusPending-this.statusFailed);
    const values=sample.slice(0,8).reverse().map(r=>Math.max(0,Number(r.amount_minor||0)));
    this.trendCount=values.length;
    if(!values.length)return;
    const max=Math.max(...values,1);
    this.trendPoints=values.map((v,i)=>`${(values.length===1?50:i*100/(values.length-1)).toFixed(1)},${(88-(v/max)*68).toFixed(1)}`).join(' ');
  }
}
