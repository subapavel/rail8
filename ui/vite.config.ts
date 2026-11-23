import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';
import { adminProxy, nodeOf } from './server/adminProxy.ts';
import { moduleServers } from './server/modules.ts';
import { uiPort } from './server/uiPort.ts';

// The development server listens on localhost only, on a fixed port, so the origins its own pages send are known.
const port = uiPort(process.env.RAIL8_UI_PORT);
const origins = [`http://localhost:${port}`, `http://127.0.0.1:${port}`];

/**
 * Sends the UI's `/admin` requests on to a node's admin API, which answers no browser. The node is RAIL8_ADMIN_URL
 * (default http://127.0.0.1:8081); RAIL8_ADMIN_TOKEN, when set, is the token the proxy adds. It is read here, on the
 * server, and never reaches the browser.
 */
function adminApi(): Plugin {
  return {
    name: 'rail8-admin-api',
    configureServer(server) {
      server.middlewares.use(adminProxy({ ...nodeOf(process.env), origins }));
    },
  };
}

export default defineConfig({
  // This entry is reached through ProComponents. Prebundle it before a screen opens so Vite does not
  // reload the page, which would drop an editor's working copy.
  optimizeDeps: {
    include: ['antd/locale/en_US'],
  },
  // the modules of the UI bring their own halves of the development server
  plugins: [react(), adminApi(), moduleServers({ origins, env: process.env })],
  server: {
    host: 'localhost',
    port,
    strictPort: true,
    // the halves add the folders they read from
    fs: { allow: ['.'] },
  },
});
