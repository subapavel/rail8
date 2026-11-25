import { homedir, tmpdir } from 'node:os';
import { join } from 'node:path';
import { uiPort } from '../server/uiPort';

/**
 * The temporary copy of the mapping corpus the @stack development server uses as its working folder. The server command
 * creates it, the scenarios read it.
 */
export const stackWorkspace = join(tmpdir(), 'rail8-stack-workspace');

/**
 * The quickstart's working folder, whose git repositories the quickstart's node deploys from: the deploy scenario's
 * development server shows it, and the scenario makes its package's repository in it.
 */
export const deployWorkspace = join(homedir(), 'rail8-workspace');

/** The port of the deploy scenario's development server: the one after the port of the other scenarios' server. */
export const deployPort = uiPort(process.env.RAIL8_UI_PORT) + 1;

/**
 * The port of the development server of the users scenario and of the change of a mapping, two after the port of the
 * other scenarios' server, over the quickstart's working folder. It starts with no token, so it runs in session mode: a
 * person logs in, and every call carries their own session.
 */
export const sessionPort = uiPort(process.env.RAIL8_UI_PORT) + 2;
