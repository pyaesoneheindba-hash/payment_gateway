import 'zone.js';
import {bootstrapApplication} from '@angular/platform-browser';
import {provideHttpClient} from '@angular/common/http';
import {provideRouter, Routes} from '@angular/router';
import {AppComponent} from './app/app.component';
import {CheckoutComponent} from './app/checkout.component';
const routes: Routes=[{path:'checkout/:token',component:CheckoutComponent},{path:'**',redirectTo:'checkout/invalid'}];
bootstrapApplication(AppComponent,{providers:[provideHttpClient(),provideRouter(routes)]});
