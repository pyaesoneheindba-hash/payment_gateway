import {inject} from '@angular/core';
import {HttpContextToken, HttpErrorResponse, HttpInterceptorFn} from '@angular/common/http';
import {from, switchMap, catchError, throwError} from 'rxjs';
import {OidcAuthService} from './oidc-auth.service';

const AUTH_RETRY = new HttpContextToken<boolean>(() => false);

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/v1/portal/')) return next(request);

  const auth = inject(OidcAuthService);

  return from(auth.validToken()).pipe(
    switchMap(token => {
      if (!token) {
        void auth.login();
        return throwError(() => new Error('Portal authentication is required'));
      }

      const authenticated = request.clone({setHeaders: {Authorization: `Bearer ${token}`}});
      return next(authenticated).pipe(
        catchError(error => {
          if (!(error instanceof HttpErrorResponse) || error.status !== 401 || request.context.get(AUTH_RETRY)) {
            return throwError(() => error);
          }

          // The server can reject a token before its local exp (revocation, not-before,
          // clock changes). Force exactly one refresh and retry the original request.
          return from(auth.refreshAccessToken(true)).pipe(
            switchMap(refreshedToken => {
              if (!refreshedToken) {
                void auth.reauthenticate();
                return throwError(() => error);
              }
              const retry = request.clone({
                context: request.context.set(AUTH_RETRY, true),
                setHeaders: {Authorization: `Bearer ${refreshedToken}`}
              });
              return next(retry);
            })
          );
        })
      );
    })
  );
};
