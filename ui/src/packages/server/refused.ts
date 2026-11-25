import { Refusal } from '../../../server/workingFolder.ts';
import type { Refused } from '../rules.ts';

/** The refusal with `status` of `refused`, one of the packages' records: its code, its English text and its values. */
export const refusal = (status: number, refused: Refused) => new Refusal(status, refused.code, refused.error, refused.values);
