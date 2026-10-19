import {Component,OnDestroy,OnInit,signal} from '@angular/core'; import {CommonModule} from '@angular/common';
import {HttpClient} from '@angular/common/http'; import {ActivatedRoute} from '@angular/router'; import QRCode from 'qrcode';
@Component({selector:'app-checkout',standalone:true,template:`
<div class="shell" style="max-width:520px;padding-top:48px"><div class="card" style="text-align:center">
<div class="checkout-brand"><img src="/assets/branding/txn-hub-logo.png" alt="TXN HUB Transaction Hub" class="checkout-brand-logo" /></div>
<h2>Scan to Pay</h2><p class="muted" *ngIf="loading()">Loading payment...</p><p class="error" *ngIf="error()">{{error()}}</p>
<ng-container *ngIf="session() as s"><img *ngIf="qrImage()" [src]="qrImage()" width="280" height="280" alt="Payment QR"/>
<div class="metric" style="margin-top:12px">{{formatAmount(s.amountMinor)}} {{s.currency}}</div><p>{{s.merchantOrderId}}</p>
<div class="metric" [class.error]="remaining()<=30">{{clock()}}</div><p class="muted">QR expires in 5 minutes</p>
<h3 [class.success]="s.status==='SUCCEEDED'" [class.error]="terminalFailure(s.status)">{{statusText(s.status)}}</h3>
<p class="muted">Do not close this page while payment is processing.</p></ng-container></div></div>`,imports:[CommonModule]})
export class CheckoutComponent implements OnInit,OnDestroy{
 loading=signal(true);error=signal('');session=signal<any|null>(null);qrImage=signal('');remaining=signal(300);private token='';private poll:any;private tick:any;
 constructor(private http:HttpClient,private route:ActivatedRoute){}
 ngOnInit(){this.token=this.route.snapshot.paramMap.get('token')||'';this.load();this.poll=setInterval(()=>this.refresh(),2000);this.tick=setInterval(()=>this.updateClock(),250);}
 ngOnDestroy(){clearInterval(this.poll);clearInterval(this.tick)}
 load(){this.http.get<any>(`/api/v1/checkout/${encodeURIComponent(this.token)}`).subscribe({next:s=>{this.session.set(s);this.loading.set(false);this.renderQr(s.qr);this.updateClock();this.handleTerminal(s)},error:e=>{this.error.set(e?.error?.error||'Checkout session not found');this.loading.set(false)}})}
 refresh(){if(!this.session()||this.isTerminal(this.session().status))return;this.http.get<any>(`/api/v1/checkout/${encodeURIComponent(this.token)}/status`).subscribe({next:s=>{const cur=this.session();this.session.set({...cur,...s});this.updateClock();this.handleTerminal(this.session())}})}
 async renderQr(v:string){if(v)this.qrImage.set(await QRCode.toDataURL(v,{width:320,margin:2,errorCorrectionLevel:'M'}));}
 updateClock(){const s=this.session();if(!s)return;const seconds=Math.max(0,Math.ceil((new Date(s.expiresAt).getTime()-Date.now())/1000));this.remaining.set(seconds);if(seconds===0&&!this.isTerminal(s.status)){this.session.set({...s,status:'EXPIRED'});this.handleTerminal(this.session())}}
 clock(){const n=this.remaining(),m=Math.floor(n/60),s=n%60;return `${m}:${String(s).padStart(2,'0')}`}
 formatAmount(n:number){return new Intl.NumberFormat().format(n)}
 statusText(s:string){return ({QR_GENERATED:'Waiting for payment…',PENDING:'Waiting for payment…',SUCCEEDED:'Payment successful',FAILED:'Payment failed',EXPIRED:'QR expired',VOIDED:'Payment voided'} as any)[s]||s}
 terminalFailure(s:string){return ['FAILED','EXPIRED','VOIDED'].includes(s)} isTerminal(s:string){return ['SUCCEEDED','FAILED','EXPIRED','VOIDED','REFUNDED'].includes(s)}
 handleTerminal(s:any){if(!s||!this.isTerminal(s.status))return;clearInterval(this.poll);setTimeout(()=>{this.http.post(`/api/v1/checkout/${encodeURIComponent(this.token)}/redirected`,{}).subscribe({error:()=>{}});const u=new URL(s.returnUrl);u.searchParams.set('paymentId',s.paymentId);u.searchParams.set('status',s.status);location.replace(u.toString())},1200)}
}
