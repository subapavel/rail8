/** At most this many lines of what git, a hook or the node says reach an answer, each cut at {@link MAX_LINE} characters. */
export const MAX_LINES = 40;
export const MAX_LINE = 200;

/**
 * `text` as an answer may carry it: the user information of every URL (`https://user:secret@host`) replaced with `***`,
 * every occurrence of `token` (the admin token) replaced with `***`, and at most {@link MAX_LINES} lines of
 * {@link MAX_LINE} characters, without trailing white space. Every text from git, a hook or the node passes through it.
 */
export function formatted(text: string, token?: string): string {
  let said = text.replace(/\b([a-z][a-z0-9+.-]*:\/\/)[^\s/@]*@/gi, '$1***@');
  if (token) said = said.split(token).join('***');
  return said
    .trimEnd()
    .split(/\r\n|\r|\n/)
    .slice(0, MAX_LINES)
    .map((line) => line.slice(0, MAX_LINE))
    .join('\n');
}
