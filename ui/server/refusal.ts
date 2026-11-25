/** The values a refusal's text names, by name: raw strings and numbers. */
export type RefusalValues = Readonly<Record<string, string | number>>;

/**
 * The codes of the UI server's own refusals: its session, its pass to the admin API, the working folder and the mapping
 * studio's API. A module's routes add the codes of their own records. A code is kebab-case, and once shipped it is never
 * renamed; the UI shows the refusal in the user's language by it.
 */
export const SERVER_CODES = [
  // the session and the pass to the admin API
  'no-credential',
  'session-changed',
  'bad-login',
  'token-mode',
  'foreign-origin',
  'bad-path',
  'sessions-refused',
  'method-not-allowed',
  'node-unavailable',
  'too-large',
  'unsupported-media-type',
  'failed',
  // the working folder
  'bad-request',
  'no-workspace',
  'working-folder-busy',
  'no-file',
  'no-folder',
  'path-leaves-folder',
  'path-refused',
  'file-too-large',
  'file-exists',
  'file-changed',
  'version-required',
  'read-only',
  'internal-error',
  // the endpoint and flow files
  'not-endpoint-file',
  'not-endpoint-text',
  'endpoint-file-refused',
  'not-flow-file',
  'not-flow-text',
  'flow-file-refused',
  // the mapping studio's API, beside the codes of its contract
  'no-route',
  'busy',
] as const;

/** The body of a refusal: `{error, code, values}`, its English text, its code and the values the text names. */
export function refusalBody(code: string, error: string, values: RefusalValues = {}): { error: string; code: string; values: RefusalValues } {
  return { error, code, values };
}
