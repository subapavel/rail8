import { resolve } from 'node:path';
import { defineConfig, mergeConfig } from 'vite';
import viteConfig from '../../vite.config.ts';

// A production build of the pictures page alone: `vite build` with this file writes it to scripts/pictures/dist, and
// `vite preview` with it serves that. The application's own build never holds it.
export default mergeConfig(
  viteConfig,
  defineConfig({
    root: resolve(import.meta.dirname, '../..'),
    build: {
      outDir: resolve(import.meta.dirname, 'dist'),
      emptyOutDir: true,
      rollupOptions: { input: resolve(import.meta.dirname, 'index.html') },
    },
    preview: { host: 'localhost', port: 4175, strictPort: true },
  }),
);
