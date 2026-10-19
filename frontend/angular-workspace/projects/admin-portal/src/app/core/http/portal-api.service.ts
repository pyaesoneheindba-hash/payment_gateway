import {Injectable} from '@angular/core';
import {HttpClient, HttpParams} from '@angular/common/http';

export interface PortalMe { portalUserId:string; role:string; merchantId?:string; }
export interface MerchantPortalLoginRepairRequest {
  username:string;
  email:string;
  displayName:string;
  temporaryPassword:string;
  requirePasswordChange:boolean;
}

export interface MerchantOnboardingRequest {
  merchantCode:string;
  merchantName:string;
  environment:'UAT'|'PRODUCTION';
  contactEmail?:string;
  redirectUrl?:string;
  keyId?:string;
  publicKey?:string;
  webhookUrl?:string;
  webhookDescription?:string;
  portalEmail?:string;
  portalUsername?:string;
  portalDisplayName?:string;
  portalTemporaryPassword?:string;
  portalRequirePasswordChange?:boolean;
}

@Injectable({providedIn:'root'})
export class PortalApiService {
  constructor(private http:HttpClient){}
  me(){return this.http.get<PortalMe>('/api/v1/portal/me');}
  summary(){return this.http.get<any>('/api/v1/portal/admin/summary');}
  dashboardActivity(hours=24){return this.http.get<any>('/api/v1/portal/admin/dashboard/activity',{params:new HttpParams().set('hours',String(hours))});}
  transactions(){return this.http.get<any[]>('/api/v1/portal/admin/transactions');}
  transactionsPage(v:{
    page:number;size:number;search?:string;merchant?:string;orderId?:string;paymentId?:string;
    amountMinor?:number|null;currency?:string;provider?:string;status?:string;webhook?:string;
    createdFrom?:string;createdTo?:string;
  }){
    let params=new HttpParams().set('page',String(v.page)).set('size',String(v.size));
    if(v.search?.trim()) params=params.set('search',v.search.trim());
    if(v.merchant?.trim()) params=params.set('merchant',v.merchant.trim());
    if(v.orderId?.trim()) params=params.set('orderId',v.orderId.trim());
    if(v.paymentId?.trim()) params=params.set('paymentId',v.paymentId.trim());
    if(v.amountMinor!==null && v.amountMinor!==undefined && Number.isFinite(Number(v.amountMinor))) params=params.set('amountMinor',String(v.amountMinor));
    if(v.currency?.trim()) params=params.set('currency',v.currency.trim());
    if(v.provider?.trim()) params=params.set('provider',v.provider.trim());
    if(v.status?.trim()) params=params.set('status',v.status.trim());
    if(v.webhook?.trim()) params=params.set('webhook',v.webhook.trim());
    if(v.createdFrom?.trim()) params=params.set('createdFrom',v.createdFrom.trim());
    if(v.createdTo?.trim()) params=params.set('createdTo',v.createdTo.trim());
    return this.http.get<any>('/api/v1/portal/admin/transactions/page',{params});
  }
  paymentWebhooks(paymentId:string){return this.http.get<any[]>(`/api/v1/portal/admin/transactions/${paymentId}/webhooks`);}
  merchants(){return this.http.get<any[]>('/api/v1/portal/admin/merchants');}
  createMerchant(v:any){return this.http.post<any>('/api/v1/portal/admin/merchants',v);}
  onboardMerchant(v:MerchantOnboardingRequest){return this.http.post<any>('/api/v1/portal/admin/merchants/onboard',v);}
  merchantReadiness(merchantId:string){return this.http.get<any>(`/api/v1/portal/admin/merchants/${merchantId}/readiness`);}
  activateMerchant(merchantId:string){return this.http.post<any>(`/api/v1/portal/admin/merchants/${merchantId}/activate`,{});}
  suspendMerchant(merchantId:string){return this.http.post<any>(`/api/v1/portal/admin/merchants/${merchantId}/suspend`,{});}
  repairMerchantPortalLogin(merchantId:string,v:MerchantPortalLoginRepairRequest){return this.http.post<any>(`/api/v1/portal/admin/merchants/${merchantId}/portal-login/repair`,v);}
  merchantRedirects(merchantId:string){return this.http.get<any[]>(`/api/v1/portal/admin/merchants/${merchantId}/redirect-uris`);}
  addRedirect(merchantId:string,redirectUrl:string){return this.http.post<any>(`/api/v1/portal/admin/merchants/${merchantId}/redirect-uris`,{redirectUrl});}
  updateRedirect(merchantId:string,redirectUriId:string,redirectUrl:string,status:'ACTIVE'|'DISABLED'){return this.http.put<any>(`/api/v1/portal/admin/merchants/${merchantId}/redirect-uris/${redirectUriId}`,{redirectUrl,status});}
  reconcile(merchantId:string,paymentId:string){return this.http.post(`/api/v1/portal/admin/transactions/${paymentId}/reconcile?merchantId=${encodeURIComponent(merchantId)}`,{});}
}
