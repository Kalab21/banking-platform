import { ArrowDownLeft, ArrowUpRight } from "lucide-react";
import { Money } from "@/components/ui/primitives";
import { formatDateTime, humanise, isCredit, maskAccountNumber } from "@/lib/format";
import type { Transaction } from "@/types/api";

/**
 * One line of activity.
 *
 * A list rather than a table, which is what makes this work on a phone: six
 * columns at 390px either overflow or shrink to unreadable, and a horizontal
 * scrollbar is not a mobile design. The same rows read correctly on a desktop.
 *
 * Direction is carried three ways — an arrow, an explicit + or −, and the
 * wording of the type — so it never depends on colour alone.
 */
export function TransactionRow({
  transaction,
  accountNumber,
  showBalanceAfter = false,
}: {
  transaction: Transaction;
  /** The account this line belongs to, when the list spans more than one. */
  accountNumber?: string | null;
  showBalanceAfter?: boolean;
}) {
  const credit = isCredit(transaction.type);
  const Icon = credit ? ArrowDownLeft : ArrowUpRight;

  return (
    <li className="flex items-center gap-3 px-5 py-3.5 sm:gap-4">
      <span
        aria-hidden="true"
        className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full ${
          credit ? "bg-positive-soft text-positive" : "bg-sunken text-ink-muted"
        }`}
      >
        <Icon className="h-4 w-4" />
      </span>

      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-ink">
          {transaction.description || humanise(transaction.type)}
        </p>
        {/*
         * Wraps rather than truncates. In a narrow column the single-line
         * version cut the transaction type to "With…", which is worse than a
         * second line.
         */}
        <p className="text-xs text-ink-subtle">
          {formatDateTime(transaction.createdAt)}
          {accountNumber ? ` · ${maskAccountNumber(accountNumber)}` : ""}
          {transaction.description ? ` · ${humanise(transaction.type)}` : ""}
        </p>
      </div>

      <div className="shrink-0 text-right">
        {/*
         * The direction comes from the transaction type, not from the sign of
         * the amount: the API records every amount as a positive number, so a
         * withdrawal of 500 and a deposit of 500 are the same figure and only
         * the type tells them apart.
         */}
        <Money
          amount={credit ? Math.abs(transaction.amount) : -Math.abs(transaction.amount)}
          currency={transaction.currency}
          signed
          size="sm"
          className={credit ? "font-semibold text-positive" : "font-semibold text-ink"}
        />
        {showBalanceAfter ? (
          <p className="tabular text-xs text-ink-subtle">
            <span className="sr-only">Balance after: </span>
            <Money amount={transaction.balanceAfter} currency={transaction.currency} size="sm" />
          </p>
        ) : null}
      </div>
    </li>
  );
}
