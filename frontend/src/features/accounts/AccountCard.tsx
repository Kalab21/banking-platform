import Link from "next/link";
import { ArrowRight, Landmark, PiggyBank, Briefcase } from "lucide-react";
import { Badge, Money, statusTone } from "@/components/ui/primitives";
import { formatCurrency, humanise, maskAccountNumber } from "@/lib/format";
import type { Account } from "@/types/api";

/**
 * One account, as a card.
 *
 * Everything on it comes from the account record: type, masked number, balance,
 * available balance and status. There is no nickname, no routing number and no
 * product name — the API does not have them, and inventing "Everyday Checking"
 * would be writing marketing copy into a balance sheet.
 *
 * The whole card is a link, but the link is an anchor wrapping the content
 * rather than a div with a click handler, so it is reachable by keyboard, can
 * be opened in a new tab, and announces itself as a link.
 */

const ICONS = {
  CHECKING: Landmark,
  SAVINGS: PiggyBank,
  BUSINESS: Briefcase,
} as const;

export function AccountCard({ account }: { account: Account }) {
  const Icon = ICONS[account.accountType] ?? Landmark;
  const overdrawn = account.balance < 0;

  return (
    <Link
      href={`/accounts/${account.id}`}
      className="group flex flex-col rounded-[var(--radius-card)] border border-line bg-surface p-5 transition-colors hover:border-line-strong hover:bg-surface-hover"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <span
            aria-hidden="true"
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-[10px] bg-primary-soft text-primary"
          >
            <Icon className="h-[18px] w-[18px]" />
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold text-ink">
              {humanise(account.accountType)} account
            </p>
            <p className="tabular text-xs text-ink-subtle">
              {maskAccountNumber(account.accountNumber)}
            </p>
          </div>
        </div>
        <Badge tone={statusTone(account.status)}>{humanise(account.status)}</Badge>
      </div>

      <div className="mt-5">
        <Money
          amount={account.balance}
          currency={account.currency}
          size="lg"
          className={overdrawn ? "text-critical" : "text-ink"}
        />
        <p className="mt-1 text-xs text-ink-subtle">
          {formatCurrency(account.availableBalance, account.currency)} available
          {account.overdraftBalance > 0
            ? ` · ${formatCurrency(account.overdraftBalance, account.currency)} overdraft used`
            : ""}
        </p>
      </div>

      <span className="mt-4 inline-flex items-center gap-1.5 text-sm font-medium text-primary">
        View account
        <ArrowRight
          aria-hidden="true"
          className="h-4 w-4 transition-transform group-hover:translate-x-0.5"
        />
      </span>
    </Link>
  );
}
