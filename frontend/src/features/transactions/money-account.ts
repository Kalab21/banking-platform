import { formatCurrency, humanise, maskAccountNumber } from "@/lib/format";
import type { Account } from "@/types/api";

/**
 * What a money form is allowed to know about an account.
 *
 * The money forms run in the browser, and anything a Server Component hands a
 * Client Component is serialised into the RSC payload that ships with the page
 * — visible in view-source whether or not it is ever rendered. Passing the
 * whole `Account` therefore published the raw `accountNumber` even though every
 * label on screen was masked.
 *
 * So the crossing is made deliberately: the server builds the strings the form
 * displays and sends those, and the account number itself never leaves it. The
 * id travels because the operation cannot be submitted without one, and it is
 * not a secret — the gateway re-checks ownership on every request, so knowing
 * an id grants nothing.
 */
export interface MoneyAccountOption {
  /** Identifies the account to the API. Ownership is enforced server-side. */
  id: number;
  /** Ready-to-render option text, e.g. `Checking ••••2024 — $11,131.99`. */
  label: string;
  /** e.g. `••••2024`, for confirmation and receipt lines. */
  maskedNumber: string;
  currency: string;
}

/** Narrows accounts to the fields the money forms may see. */
export function toMoneyAccountOptions(accounts: Account[]): MoneyAccountOption[] {
  return accounts.map((account) => ({
    id: account.id,
    label: `${humanise(account.accountType)} ${maskAccountNumber(account.accountNumber)} — ${formatCurrency(
      account.balance,
      account.currency,
    )}`,
    maskedNumber: maskAccountNumber(account.accountNumber),
    currency: account.currency,
  }));
}
