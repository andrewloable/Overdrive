import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { LoginComponent } from './pages/login/login.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  {
    path: '',
    canActivate: [authGuard],
    children: [
      { path: '', loadComponent: () => import('./pages/dashboard/dashboard.component') },
      { path: 'recording', loadComponent: () => import('./pages/recording/recording.component') },
      { path: 'surveillance', loadComponent: () => import('./pages/surveillance/surveillance.component') },
      { path: 'events', loadComponent: () => import('./pages/events/events.component') },
      { path: 'trips', loadComponent: () => import('./pages/trips/trips.component') },
      { path: 'vehicle', loadComponent: () => import('./pages/vehicle/vehicle.component') },
      { path: 'notifications', loadComponent: () => import('./pages/notifications/notifications.component') },
      { path: 'performance', loadComponent: () => import('./pages/performance/performance.component') },
      { path: 'settings', loadComponent: () => import('./pages/settings/settings.component') },
      { path: 'about', loadComponent: () => import('./pages/about/about.component') },
    ],
  },
  { path: '**', redirectTo: '' },
];
