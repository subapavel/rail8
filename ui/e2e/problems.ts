import type { Page } from '@playwright/test';

/** The line the browser writes for a request answered 503; under `/api` that answer is `busy`. */
const REFUSED_503 = 'Failed to load resource: the server responded with a status of 503 ';

/**
 * Collects every `console.error` and `pageerror` of the page: a scenario ends with the list empty. The browser's own line
 * for a 503 of the studio's API is left out: that answer is `busy`, a node running as many calls of its mapping module
 * as it takes, and the studio sends the load again.
 */
export function collectProblems(page: Page): string[] {
  const problems: string[] = [];
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    if (message.text().startsWith(REFUSED_503) && /^https?:\/\/[^/]+\/api\//.test(message.location().url)) return;
    problems.push(message.text());
  });
  page.on('pageerror', (error) => problems.push(error.message));
  return problems;
}
