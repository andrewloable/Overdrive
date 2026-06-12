// zone.js MUST be imported before Angular bootstraps. Unlike an Angular CLI
// build (which auto-injects it via angular.json `polyfills`), this Vite +
// @analogjs/vite-plugin-angular build pulls in nothing automatically. The app
// uses provideZoneChangeDetection() (see app.config.ts), so without this the
// NgZone constructor throws NG0908 and the page renders blank.
import 'zone.js';

import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';

bootstrapApplication(AppComponent, appConfig).catch(console.error);
