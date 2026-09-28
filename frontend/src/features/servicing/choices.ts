import type { Account, AmortizationScheduleRow, CreditCard, PayoffQuote } from "@/types/api";
import { formatCurrency, formatDate } from "@/lib/format";
import type { AmountChoice } from "@/features/servicing/ServicingPayment";

/**
 * The amounts a payment form offers, derived from what the backend reported.
 *
 * Nothing here decides anything. The backend caps a payment at what is owed and
 * works a payoff out for itself; these are the figures the customer is shown to
 * choose between, and every one of them is read from a stored value.
 */

const cents = (n: number) => Math.round(n * 100) / 100;

/** Accounts a payment can come from, or a loan's money can go to. */
export function servicingAccounts(accounts: Account[], currency: string): Account[] {
  // No conversion exists, so only accounts in the product's currency. A frozen
  // or closed account would be refused by the backend; it is not offered.
  return accounts.filter(
    (a) => a.currency === currency && (a.status === "ACTIVE" || a.status === "OVERDRAWN"),
  );
}

/** The next instalment still owed, with what is left to pay on it. */
export function nextInstalment(schedule: AmortizationScheduleRow[]) {
  const row = [...schedule]
    .sort((a, b) => a.paymentNumber - b.paymentNumber)
    .find((r) => r.status !== "PAID");
  if (!row) return null;
  const left = cents(row.scheduledPayment - (row.amountPaid ?? 0));
  return left > 0 ? { row, left } : null;
}

export function repaymentChoices(schedule: AmortizationScheduleRow[]): AmountChoice[] {
  const next = nextInstalment(schedule);
  if (!next) return [];
  const partial = next.row.status === "PARTIAL";
  return [
    {
      id: "instalment",
      label: partial
        ? `Rest of instalment ${next.row.paymentNumber}`
        : `Instalment ${next.row.paymentNumber}`,
      amount: next.left,
      hint: `Due ${formatDate(next.row.dueDate)}`,
    },
  ];
}

export function payoffChoices(quote: PayoffQuote | null, currency: string): AmountChoice[] {
  if (!quote || quote.totalPayoffAmount <= 0) return [];
  return [
    {
      id: "payoff",
      label: "Payoff amount today",
      amount: quote.totalPayoffAmount,
      hint: `Remaining balance plus ${formatCurrency(quote.accruedInterest, currency)} interest for this month`,
    },
  ];
}

/**
 * Minimum, statement balance and current balance, each capped at what is owed
 * now and each offered once: a statement balance equal to the current balance
 * is one choice, not two.
 */
export function cardPaymentChoices(card: CreditCard): AmountChoice[] {
  const owed = cents(card.currentBalance);
  if (owed <= 0) return [];
  const candidates: AmountChoice[] = [
    {
      id: "minimum",
      label: "Minimum payment",
      amount: cents(Math.min(card.minimumPaymentDue, owed)),
      hint: card.paymentDueDate ? `Due ${formatDate(card.paymentDueDate)}` : undefined,
    },
    { id: "statement", label: "Statement balance", amount: cents(Math.min(card.statementBalance, owed)) },
    { id: "current", label: "Current balance", amount: owed },
  ];
  const seen = new Set<number>();
  return candidates.filter((c) => {
    if (c.amount <= 0 || seen.has(c.amount)) return false;
    seen.add(c.amount);
    return true;
  });
}
