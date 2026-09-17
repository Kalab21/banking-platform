/**
 * Social Security number handling in the browser.
 *
 * The number is typed here, formatted for reading, and sent once. It is never
 * written to localStorage, sessionStorage, a cookie, a query parameter or a URL
 * fragment, and the server keeps only its last four digits — so the full value
 * exists nowhere after the request that carried it.
 */

/** Digits only, capped at nine so a stray keystroke cannot extend it. */
export function normalizeSsn(value: string): string {
  return value.replace(/\D/g, "").slice(0, 9);
}

/**
 * Groups as the customer types: `123`, `123-45`, `123-45-6789`.
 *
 * Progressive rather than all-at-once, so the hyphens appear where they are
 * expected instead of jumping in at the end.
 */
export function formatSsn(value: string): string {
  const digits = normalizeSsn(value);
  if (digits.length <= 3) return digits;
  if (digits.length <= 5) return `${digits.slice(0, 3)}-${digits.slice(3)}`;
  return `${digits.slice(0, 3)}-${digits.slice(3, 5)}-${digits.slice(5)}`;
}

/** The last four digits, which is the only part the server stores. */
export function lastFourOfSsn(value: string): string {
  return normalizeSsn(value).slice(-4);
}

/**
 * How a number on file is shown: `•••-••-6789`.
 *
 * Takes the four digits the server returned, not a full number — there is no
 * full number to take.
 */
export function maskedSsn(last4: string | null | undefined): string {
  if (!last4) return "";
  return `•••-••-${last4}`;
}
