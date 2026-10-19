import {Component} from '@angular/core';
import {RouterLink, RouterLinkActive, RouterOutlet} from '@angular/router';
import {OidcAuthService} from './core/auth/oidc-auth.service';
import {PortalLiveService} from './core/live/portal-live.service';
import {PortalSessionService} from './core/auth/portal-session.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <div class="app-frame">
      <header class="topbar">
        <div class="topbar-inner">
          <a class="brand" routerLink="/dashboard" aria-label="TXN HUB Merchant Portal" (click)="closeNav()">
            <img class="brand-logo" src="/assets/branding/txn-hub-logo.png" alt="TXN HUB Transaction Hub" />
            <span class="portal-badge">Merchant</span>
          </a>

          <button class="nav-toggle" type="button" aria-label="Toggle navigation" [attr.aria-expanded]="navOpen" (click)="navOpen=!navOpen">
            <span></span><span></span><span></span>
          </button>

          <nav class="primary-nav" [class.open]="navOpen" aria-label="Merchant navigation">
            <a routerLink="/dashboard" routerLinkActive="active" [routerLinkActiveOptions]="{exact:true}" (click)="closeNav()">Dashboard</a>
            <a routerLink="/transactions" routerLinkActive="active" (click)="closeNav()">Transactions</a>
            <a routerLink="/integrations" routerLinkActive="active" (click)="closeNav()">API Keys & Webhooks</a>
            <a routerLink="/sdk" routerLinkActive="active" (click)="closeNav()">SDKs</a>
          </nav>

          <div class="account-menu">
            <span class="notification-dot" aria-hidden="true"></span>
            <div class="avatar">M</div>
            <div class="account-copy">
              <strong>Merchant User</strong>
              <span>Secure portal</span>
            </div>
            <button class="text-button" type="button" (click)="logout()">Sign out</button>
          </div>
        </div>
      </header>

      <main class="page-shell">
        <router-outlet />
      </main>
    </div>
  `
})
export class AppComponent {
  navOpen=false;
  constructor(private auth: OidcAuthService, private live:PortalLiveService, private session:PortalSessionService) {}
  closeNav(){this.navOpen=false;}
  logout() { this.live.disconnect(); this.session.clear(); this.auth.logout(); }
}
