import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';

export interface PortalMe { portalUserId: string; role: string; merchantId?: string; merchantStatus?: string; }
@Injectable({providedIn:'root'})
export class PortalApiService {
  constructor(private http: HttpClient) {}
  me(){ return this.http.get<PortalMe>('/api/v1/portal/me'); }
  summary(){ return this.http.get<any>('/api/v1/portal/merchant/summary'); }
  transactions(){ return this.http.get<any[]>('/api/v1/portal/merchant/transactions'); }
  reconcile(id:string){ return this.http.post(`/api/v1/portal/merchant/transactions/${id}/reconcile`,{}); }
  keys(){ return this.http.get<any[]>('/api/v1/portal/merchant/api-keys'); }
  registerKey(keyId:string,publicKey:string){ return this.http.post('/api/v1/portal/merchant/api-keys',{keyId,publicKey}); }
  webhooks(){ return this.http.get<any[]>('/api/v1/portal/merchant/webhooks'); }
  addWebhook(url:string){ return this.http.post('/api/v1/portal/merchant/webhooks',{url,description:'Merchant portal'}); }
}
