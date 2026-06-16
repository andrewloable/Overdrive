import { defineConfig } from 'vite';
import angular from '@analogjs/vite-plugin-angular';

export default defineConfig({
  plugins: [angular()],
  // Per-build cache-bust token appended to the i18n catalog URL (see
  // app.config.ts). The catalog URLs are unhashed (unlike the content-hashed
  // JS/CSS bundle), so without this a browser that cached /i18n/en.json under
  // the old max-age=86400 would keep serving a stale catalog for 24h after an
  // app update — rendering raw keys. A fresh ?v=<build> per release makes the
  // new bundle request a URL the browser has never cached, so the catalog
  // refreshes immediately on the next load (no manual cache-clear / re-add).
  define: {
    __I18N_BUILD__: JSON.stringify(String(Date.now())),
  },
  build: {
    outDir: 'dist',
  },
  server: {
    proxy: {
      '/bladewatch.v1': 'http://127.0.0.1:8080',
      '/api': 'http://127.0.0.1:8080',
      '/status': 'http://127.0.0.1:8080',
      '/auth': 'http://127.0.0.1:8080',
    },
  },
});
