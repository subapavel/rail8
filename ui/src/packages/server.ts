import { nodeOf } from '../../server/adminProxy.ts';
import type { ModuleServer } from '../../server/modules.ts';
import { checkGit } from './server/git.ts';
import { gitPackages } from './server/routes.ts';
import { runner, type Runner } from './server/runner.ts';
import { realTimer } from './server/timer.ts';

/**
 * The packages module's half of the development server, the git half: it serves the packages of the working folder
 * that RAIL8_WORKSPACE names under `/git`, through the author's own git found on PATH, checked once when the server
 * starts, and reads what the node at RAIL8_ADMIN_URL runs with RAIL8_ADMIN_TOKEN. Writes are refused under
 * RAIL8_READ_ONLY=true. When the development server closes or restarts, every git process it started is stopped.
 */
const packagesServer: ModuleServer = ({ origins, env }) => {
  let git: Runner | undefined;
  return {
    name: 'rail8-git-packages',
    configureServer(server) {
      git = runner({ env, timer: realTimer });
      const options = {
        workspace: env.RAIL8_WORKSPACE?.trim() || undefined,
        writable: env.RAIL8_READ_ONLY?.trim() !== 'true',
        origins,
        node: nodeOf(env),
        git,
        ready: checkGit(git),
        timer: realTimer,
      };
      server.middlewares.use(gitPackages(options));
    },
    // Vite calls this when the development server closes, and on a restart once the new server's halves are made
    async closeBundle() {
      await git?.close();
    },
  };
};

export default packagesServer;
