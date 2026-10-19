import {ChangeDetectorRef, Component, DestroyRef, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {auditTime, catchError, switchMap} from 'rxjs/operators';
import {merge, of, timer} from 'rxjs';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {PortalApiService,PortalMe} from '../../core/http/portal-api.service';
import {PortalSessionService} from '../../core/auth/portal-session.service';
import {LiveConnectionState, PortalLiveService} from '../../core/live/portal-live.service';

@Component({selector:'app-transactions',standalone:true,imports:[CommonModule],template:`
  <section class="page-heading">
    <div><div class="page-kicker">Payments</div><h2>Transactions</h2><p>Review your merchant payment activity and reconcile eligible transactions.</p></div>
    <div class="live-state" [class.degraded]="liveState!=='live'"><span class="live-dot"></span><span>{{liveLabel}}</span><span class="live-time" *ngIf="lastUpdated">{{rows.length}} records · {{lastUpdated|date:'mediumTime'}}</span></div>
  </section>
  <section class="card table-card" style="margin-top:0">
    <div class="panel-title"><h3>Transaction history</h3><div class="panel-actions"><span class="muted">SSE live · 60s safety sync</span><button class="btn secondary" type="button" (click)="load()">Refresh now</button></div></div>
    <div class="table-wrap" *ngIf="rows.length; else empty"><table class="responsive-table"><tr><th>Order</th><th>Amount</th><th>Provider</th><th>Status</th><th>Created</th><th>Action</th></tr><tr *ngFor="let p of rows"><td data-label="Order">{{p.merchant_order_id}}</td><td data-label="Amount">{{p.amount_minor}} {{p.currency}}</td><td data-label="Provider">{{p.selected_provider_code||'—'}}</td><td data-label="Status"><span class="pill">{{p.status}}</span></td><td data-label="Created">{{p.created_at|date:'short'}}</td><td data-label="Action"><button *ngIf="canReconcile" class="btn secondary" (click)="reconcile(p.payment_id)">Reconcile</button><span *ngIf="!canReconcile">—</span></td></tr></table></div>
    <ng-template #empty><div class="empty-state">No transactions are available.</div></ng-template>
  </section>`})
export class TransactionsComponent implements OnInit {
  readonly safetySyncSeconds=60;
  rows:any[]=[];me?:PortalMe;lastUpdated?:Date;liveState:LiveConnectionState='connecting';
  get canReconcile(){return this.me?.role==='MERCHANT_ADMIN'||this.me?.role==='MERCHANT_FINANCE';}
  get liveLabel(){return this.liveState==='live'?'Live':this.liveState==='reconnecting'?'Reconnecting…':this.liveState==='connecting'?'Connecting…':'Offline';}
  constructor(private api:PortalApiService,private live:PortalLiveService,private cdr:ChangeDetectorRef,private destroyRef:DestroyRef,private session:PortalSessionService){}
  ngOnInit(){
    this.live.acquire();
    this.destroyRef.onDestroy(()=>this.live.release());
    this.live.state$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(state=>{this.liveState=state;this.cdr.detectChanges();});
    this.me=this.session.current;
    merge(
      of(null),
      timer(this.safetySyncSeconds*1000,this.safetySyncSeconds*1000),
      this.live.events$.pipe(auditTime(120))
    ).pipe(
      switchMap(()=>this.api.transactions().pipe(catchError(()=>of(null)))),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(x=>{
      if(x!==null){this.rows=x;this.lastUpdated=new Date();}
      this.cdr.detectChanges();
    });
  }
  load(){this.api.transactions().subscribe({next:x=>{this.rows=x;this.lastUpdated=new Date();this.cdr.detectChanges();},error:()=>this.cdr.detectChanges()})}
  reconcile(id:string){this.api.reconcile(id).subscribe({next:()=>this.load(),error:()=>this.cdr.detectChanges()});}
}
