import {inject} from '@angular/core';
import {CanActivateFn} from '@angular/router';
import {OidcAuthService} from './oidc-auth.service';
import {PortalSessionService} from './portal-session.service';

export const authGuard: CanActivateFn = async () => {
  const auth = inject(OidcAuthService);
  const session = inject(PortalSessionService);

  const authenticated = await auth.ensureAuthenticated();
  if (!authenticated) {
    session.clear();
    return false;
  }

  try {
    await session.ensureLoaded();
  } catch {
    // Connectivity/service-discovery errors are rendered by the route itself. Authentication
    // failures are already refreshed/retried centrally by the HTTP interceptor.
  }
  return true;
};
