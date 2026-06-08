import { Component, inject, signal, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly deviceId = signal<string>('Loading...');
  readonly accessCode = signal<string>('');
  readonly loading = signal<boolean>(false);
  readonly error = signal<string>('');
  readonly statusMessage = signal<{ text: string; type: 'error' | 'success' } | null>(null);

  ngOnInit(): void {
    if (this.auth.isAuthenticated()) {
      this.router.navigate(['/']);
      return;
    }
    this.auth.getStatus().subscribe({
      next: resp => this.deviceId.set(resp.deviceId || 'Unknown device'),
      error: () => this.deviceId.set('Connection error'),
    });
  }

  onSubmit(): void {
    const code = this.accessCode().trim().toLowerCase();
    this.error.set('');
    this.statusMessage.set(null);

    if (!code) {
      this.error.set('Please enter your access code');
      return;
    }
    if (code.length !== 8) {
      this.error.set('Access code must be 8 characters');
      return;
    }
    const devId = this.deviceId();
    if (!devId || devId === 'Connection error' || devId === 'Unknown device' || devId === 'Loading...') {
      this.statusMessage.set({ text: 'Device ID not loaded. Please refresh.', type: 'error' });
      return;
    }

    const fullToken = devId + '-' + code;
    this.loading.set(true);

    this.auth.login(fullToken).subscribe({
      next: () => {
        this.statusMessage.set({ text: 'Login successful! Redirecting...', type: 'success' });
        setTimeout(() => this.router.navigate(['/']), 500);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.error.set(AuthService.connectErrorMessage(err));
      },
    });
  }

  onKeyPress(event: KeyboardEvent): void {
    if (event.key === 'Enter') this.onSubmit();
  }
}
