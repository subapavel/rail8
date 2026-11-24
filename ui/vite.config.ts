import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';
import { adminProxy, nodeOf, trustAdminCa } from './server/adminProxy.ts';
import { moduleServers } from './server/modules.ts';
import { loopbackHost, modeOf, uiSession } from './server/session.ts';
import { uiPort } from './server/uiPort.ts';

// The development server listens on localhost only, on a fixed port, so the origins its own pages send are known.
const port = uiPort(process.env.RAIL8_UI_PORT);
const origins = [`http://localhost:${port}`, `http://127.0.0.1:${port}`];

/**
 * The UI server's session, then the `/admin` proxy, in front of every half. The mode is chosen once, at the start:
 * token mode with RAIL8_TOKEN, or with RAIL8_ADMIN_TOKEN, the shared token, and session mode without either, where a
 * person logs in and every call carries their own session. A token is read here, on the server, and never reaches the
 * browser. The proxy sends the UI's `/admin` requests on to a node's admin API, which answers no browser: RAIL8_ADMIN_URL
 * (default http://127.0.0.1:8081). The server does not start unless it listens on loopback, in either mode, nor for
 * plain HTTP to a host other than loopback unless RAIL8_ADMIN_TLS=off; for https://, RAIL8_ADMIN_CA, when set, is the one
 * CA its calls to the node trust.
 */
function adminApi(): Plugin {
  return {
    name: 'rail8-admin-api',
    configureServer(server) {
      const mode = modeOf(process.env, origins, (line) => server.config.logger.warn(line));
      if (!loopbackHost(server.config.server.host)) {
        throw new Error(`the UI server listens on loopback only, not on ${String(server.config.server.host)}: start it without --host`);
      }
      trustAdminCa(process.env);
      const { target } = nodeOf(process.env);
      server.middlewares.use(uiSession({ mode, origins, target }));
      server.middlewares.use(adminProxy({ target, origins }));
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
