/** The node's words as a sentence: its first letter capital, and a full stop. */
export const sentence = (text: string) => `${text.charAt(0).toUpperCase()}${text.slice(1)}${/[.!?]$/.test(text) ? '' : '.'}`;

/** Why something failed, as a sentence. */
export const reasonOf = (error: unknown) => (error instanceof Error ? sentence(error.message) : sentence(String(error)));
