import { defineConfig } from 'vite';
import angular from '@analogjs/vite-plugin-angular';

export default defineConfig({
  plugins: [angular()],
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
