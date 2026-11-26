/** The characters of a generated password: letters and digits, without those read alike (0 O o, 1 I l). */
export const GENERATED_ALPHABET = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789';

/** The length of a generated password: 20 characters of the alphabet, about 116 random bits. */
export const GENERATED_LENGTH = 20;

/** Bytes from `top` up are skipped, so that every character is as likely as the others. */
const top = 256 - (256 % GENERATED_ALPHABET.length);

/**
 * A strong password for the user `name`: 20 characters of `GENERATED_ALPHABET`, one for each byte that `random` puts
 * into the array it is given and that is below a multiple of the alphabet's size, and never containing `name` in any
 * letter case, which the node refuses. It is only returned: nothing keeps or logs it.
 */
export function generatePassword(name: string, random: (into: Uint8Array<ArrayBuffer>) => unknown = (into) => crypto.getRandomValues(into)): string {
  const avoided = name.toLowerCase();
  for (;;) {
    let password = '';
    while (password.length < GENERATED_LENGTH) {
      const bytes = new Uint8Array(GENERATED_LENGTH - password.length);
      random(bytes);
      for (const byte of bytes) {
        if (byte < top) password += GENERATED_ALPHABET[byte % GENERATED_ALPHABET.length];
      }
    }
    if (avoided === '' || !password.toLowerCase().includes(avoided)) return password;
  }
}
