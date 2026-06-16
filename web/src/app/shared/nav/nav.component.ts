import { Component, signal } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

@Component({
  selector: 'app-nav',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, TranslateModule],
  templateUrl: './nav.component.html',
  styleUrl: './nav.component.scss',
})
export class NavComponent {
  readonly overflowOpen = signal(false);

  toggleOverflow(): void {
    this.overflowOpen.update(v => !v);
  }

  closeOverflow(): void {
    this.overflowOpen.set(false);
  }
}
