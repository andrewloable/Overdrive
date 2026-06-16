import { ApplicationConfig, importProvidersFrom, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { TranslateModule, TranslateLoader } from '@ngx-translate/core';
import { TranslateHttpLoader } from '@ngx-translate/http-loader';
import { HttpClient } from '@angular/common/http';
import { routes } from './app.routes';
import { provideConnect } from './core/connect/connect.provider';

// Injected by Vite `define` at build time (see vite.config.ts). A fresh value
// per build so the catalog URL changes every release, bypassing any stale
// browser-cached /i18n/<lang>.json. Falls back to a constant when undefined
// (e.g. unit tests run outside the Vite build) so the loader still works.
declare const __I18N_BUILD__: string;
const I18N_BUILD = typeof __I18N_BUILD__ !== 'undefined' ? __I18N_BUILD__ : 'dev';

export function createTranslateLoader(http: HttpClient) {
  // The daemon's /i18n/ route strips the ?query before disk lookup, so the
  // cache-bust token resolves to the same en.json/<lang>.json on disk.
  return new TranslateHttpLoader(http, '/i18n/', `.json?v=${I18N_BUILD}`);
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(),
    provideConnect(),
    importProvidersFrom(
      TranslateModule.forRoot({
        defaultLanguage: 'en',
        loader: {
          provide: TranslateLoader,
          useFactory: createTranslateLoader,
          deps: [HttpClient],
        },
      })
    ),
  ],
};
