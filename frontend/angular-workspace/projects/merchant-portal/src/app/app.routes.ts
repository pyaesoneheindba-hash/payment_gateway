import {Routes} from '@angular/router';
import {authGuard} from './core/auth/auth.guard';
import {AuthCallbackComponent} from './core/auth/auth-callback.component';
import {DashboardComponent} from './features/dashboard/dashboard.component';
import {TransactionsComponent} from './features/transactions/transactions.component';
import {IntegrationsComponent} from './features/integrations/integrations.component';
import {SdkComponent} from './features/sdk/sdk.component';
export const routes:Routes=[
  {path:'auth/callback',component:AuthCallbackComponent},
  {path:'dashboard',component:DashboardComponent,canActivate:[authGuard]},
  {path:'transactions',component:TransactionsComponent,canActivate:[authGuard]},
  {path:'integrations',component:IntegrationsComponent,canActivate:[authGuard]},
  {path:'sdk',component:SdkComponent,canActivate:[authGuard]},
  {path:'',pathMatch:'full',redirectTo:'dashboard'},
  {path:'**',redirectTo:'dashboard'}
];
