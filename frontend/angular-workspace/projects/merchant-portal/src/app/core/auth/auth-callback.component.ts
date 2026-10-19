import {Component, OnInit, signal} from '@angular/core';
import {Router} from '@angular/router';
import {OidcAuthService} from './oidc-auth.service';
import {PortalSessionService} from './portal-session.service';

@Component({selector:'app-auth-callback',standalone:true,template:`<div class="card"><p>{{message()}}</p></div>`})
export class AuthCallbackComponent implements OnInit {
  message = signal('Completing secure sign-in…');
  constructor(private auth: OidcAuthService, private session:PortalSessionService, private router: Router) {}

  async ngOnInit() {
    try {
      await this.auth.handleCallback();
      const me = await this.session.ensureLoaded(true);
      const requested = this.auth.consumeReturnUrl('/dashboard');
      const target = me.merchantStatus === 'PENDING' ? '/integrations' : requested;
      await this.router.navigateByUrl(target, {replaceUrl:true});
    } catch (e) {
      this.session.clear();
      this.auth.clear();
      this.message.set(e instanceof Error ? e.message : 'Sign-in failed');
    }
  }
}
