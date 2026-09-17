/**
 * US phone entry helpers.
 *
 * The console is a US-only demonstration, so a ten-digit national number is the
 * only shape it formats. Anything else is left as the customer typed it rather
 * than being mangled into a pattern it does not fit — a half-formatted
 * international number is worse than an unformatted one.
 */

/** Digits only, which is what gets stored and sent. */
export function normalizePhone(value: string): string {
  return value.replace(/\D/g, "").slice(0, 15);
}

/**
 * Formats as the customer types: 2402881031 becomes (240) 288-1031.
 *
 * Partial input formats progressively, so the parentheses and dash appear as
 * the number is entered rather than all at once at the end.
 */
export function formatPhone(value: string): string {
  const raw = normalizePhone(value);

  // A pasted number often arrives with the country code attached. Eleven
  // digits beginning with 1 is that same US number, so format it as one rather
  // than treating it as something foreign.
  const digits = raw.length === 11 && raw.startsWith("1") ? raw.slice(1) : raw;

  if (digits.length === 0) return "";
  if (digits.length <= 3) return `(${digits}`;
  if (digits.length <= 6) return `(${digits.slice(0, 3)}) ${digits.slice(3)}`;
  if (digits.length <= 10) {
    return `(${digits.slice(0, 3)}) ${digits.slice(3, 6)}-${digits.slice(6)}`;
  }
  // More than ten digits is not a US national number; show it plainly.
  return digits;
}
