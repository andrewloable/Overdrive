import { Component, inject } from '@angular/core';
import { RouterOutlet, Router, NavigationEnd } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { NavComponent } from './shared/nav/nav.component';
import { filter, map, startWith } from 'rxjs/operators';
import { toSignal } from '@angular/core/rxjs-interop';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, NavComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent {
  private readonly router = inject(Router);
  private readonly translate = inject(TranslateService);

  constructor() {
    const saved = localStorage.getItem('bw_lang') ?? 'en';
    this.translate.setDefaultLang('en');
    this.translate.use(saved);
  }

  readonly showNav = toSignal(
    this.router.events.pipe(
      filter(e => e instanceof NavigationEnd),
      map(e => (e as NavigationEnd).urlAfterRedirects !== '/login'),
      startWith(typeof window !== 'undefined'
        ? !window.location.pathname.includes('/login')
        : true),
    ),
    { initialValue: true },
  );
}
