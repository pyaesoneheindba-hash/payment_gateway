import {Injectable} from '@angular/core';
import {firstValueFrom} from 'rxjs';
import {PortalApiService, PortalMe} from '../http/portal-api.service';

@Injectable({providedIn:'root'})
export class PortalSessionService {
  private currentValue?:PortalMe;
  private loadPromise?:Promise<PortalMe>;
  private lastErrorValue:any;

  constructor(private api:PortalApiService){}

  get current():PortalMe|undefined{return this.currentValue;}
  get lastError():any{return this.lastErrorValue;}

  async ensureLoaded(force=false):Promise<PortalMe>{
    if(this.currentValue && !force)return this.currentValue;
    if(this.loadPromise && !force)return this.loadPromise;
    this.lastErrorValue=undefined;
    this.loadPromise=firstValueFrom(this.api.me())
      .then(me=>{this.currentValue=me;return me;})
      .catch(error=>{this.currentValue=undefined;this.lastErrorValue=error;throw error;})
      .finally(()=>{this.loadPromise=undefined;});
    return this.loadPromise;
  }

  clear(){this.currentValue=undefined;this.lastErrorValue=undefined;this.loadPromise=undefined;}
}
