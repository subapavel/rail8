/** The port used by the development server, its origin checks and the stack tests. */
export function uiPort(value: string | undefined): number {
  const port = Number(value ?? 5173);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('RAIL8_UI_PORT must be an integer from 1 to 65535');
  return port;
}
