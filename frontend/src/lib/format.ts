/** Presentation helpers. Pure functions — no framework, easy to test. */

/** Formats a monetary amount. Negative values keep their sign (overdrafts are real). */
export function formatCurrency(amount: number | null | undefined, currency = "USD"): string {
  if (amount === null || amount === undefined || Number.isNaN(amount)) return "—";
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

/** Compact form for dashboard tiles: $12.3K, $1.2M. Falls back to full format when small. */
export function formatCompactCurrency(amount: number | null | undefined, currency = "USD"): string {
  if (amount === null || amount === undefined || Number.isNaN(amount)) return "—";
  if (Math.abs(amount) < 10_000) return formatCurrency(amount, currency);
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(amount);
}

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return "—";
  return new Intl.NumberFormat("en-US").format(value);
}

/** Percentage from a rate already expressed in percent (e.g. 6.00 -> "6.00%"). */
export function formatPercent(rate: number | null | undefined): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) return "—";
  return `${rate.toFixed(2)}%`;
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return new Intl.DateTimeFormat("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
    /*
     * A date with no time — a date of birth, say — is parsed as UTC midnight,
     * and formatting that in a timezone behind UTC shows the day before. A
     * birthday is a calendar date rather than an instant, so it is read back in
     * the calendar it was written in.
     */
    timeZone: /^\d{4}-\d{2}-\d{2}$/.test(iso) ? "UTC" : undefined,
  }).format(d);
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return new Intl.DateTimeFormat("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(d);
}

/**
 * Shows only the last four digits of a card number.
 *
 * The API no longer sends a full card number — `CreditCardResponse` carries a
 * ready-made `maskedCardNumber`. This is kept as a defensive helper for any
 * value of uncertain origin: anything unexpected collapses to a safe mask
 * rather than leaking the input.
 */
export function maskCardNumber(cardNumber: string | null | undefined): string {
  if (!cardNumber) return "•••• ••••";
  const digits = cardNumber.replace(/\D/g, "");
  if (digits.length < 4) return "•••• ••••";
  return `•••• ${digits.slice(-4)}`;
}

/** Masks all but the last four characters of an account number. */
export function maskAccountNumber(accountNumber: string | null | undefined): string {
  if (!accountNumber) return "—";
  if (accountNumber.length <= 4) return accountNumber;
  return `••••${accountNumber.slice(-4)}`;
}

/**
 * Words that are abbreviations, not words.
 *
 * Title-casing every part of an enum turns EXTERNAL_ACH into "External Ach",
 * which is not how anyone writes it and reads like a typo on a payments page.
 */
const ACRONYMS = new Set(["ach", "iban", "swift", "atm", "apr", "kyc", "usd", "eur", "gbp"]);

/** Turns SCREAMING_SNAKE enum values into readable labels. */
export function humanise(value: string | null | undefined): string {
  if (!value) return "—";
  return value
    .toLowerCase()
    .split("_")
    .map((word) =>
      ACRONYMS.has(word) ? word.toUpperCase() : word.charAt(0).toUpperCase() + word.slice(1),
    )
    .join(" ");
}

/** True when a transaction adds money to the account it belongs to. */
export function isCredit(type: string): boolean {
  return type === "DEPOSIT" || type === "TRANSFER_IN";
}
