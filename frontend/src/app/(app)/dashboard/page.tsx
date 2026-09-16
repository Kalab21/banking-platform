import type { Metadata } from "next";
import Link from "next/link";
import { requireSession } from "@/lib/session";
import {
  getAccounts,
  getCreditCards,
  getKycDocuments,
  getLoans,
  getNotifications,
  getTransactions,
  getUser,
  getUserStats,
} from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  ErrorState,
  PageHeader,
  StatTile,
  statusTone,
} from "@/components/ui/primitives";
import {
  formatCurrency,
  formatDate,
  humanise,
  isCredit,
  maskAccountNumber,
} from "@/lib/format";
import { BalanceTrendChart, type BalancePoint } from "@/features/dashboard/BalanceTrendChart";

export const metadata: Metadata = { title: "Overview" };

/** Below this many transactions a line chart would imply a trend that isn't there. */
const MIN_POINTS_FOR_CHART = 4;

export default async function DashboardPage() {
  const session = await requireSession();

  let data;
  try {
    const [profile, accounts, loans, cards, notifications, kyc, stats] = await Promise.all([
      getUser(session.userId),
      getAccounts(session.userId),
      getLoans(session.userId),
      getCreditCards(session.userId),
      getNotifications(session.userId, 0, 5),
      getKycDocuments(session.userId),
      getUserStats(session.userId),
    ]);
    data = { profile, accounts, loans, cards, notifications, kyc, stats };
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Overview" />
          <ErrorState title="We could not load your dashboard" message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  const { profile, accounts, loans, cards, notifications, stats } = data;

  const totalBalance = accounts.reduce((sum, a) => sum + a.balance, 0);
  const activeLoans = loans.filter((l) => l.status === "ACTIVE");
  const loanOutstanding = activeLoans.reduce((sum, l) => sum + l.remainingBalance, 0);
  const cardBalance = cards.reduce((sum, c) => sum + c.currentBalance, 0);
  const currency = accounts[0]?.currency ?? "USD";

  // The trend is drawn from one account's real recorded balances. Pick whichever
  // account actually has history rather than whichever happens to be first —
  // charting an untouched savings account would say nothing. With too few
  // transactions the panel still degrades to a summary rather than implying a trend.
  const histories = await Promise.all(
    accounts.map(async (account) => ({
      account,
      transactions: (await getTransactions(account.id, 0, 12))?.content ?? [],
    })),
  );
  const busiest = histories.reduce<(typeof histories)[number] | undefined>(
    (best, current) =>
      best === undefined || current.transactions.length > best.transactions.length ? current : best,
    undefined,
  );

  const primaryAccount = busiest?.account;
  const recent = busiest?.transactions ?? [];
  const points: BalancePoint[] = [...recent]
    .reverse()
    .map((t) => ({ at: t.createdAt, balance: t.balanceAfter }));

  return (
    <>
      <PageHeader
        title={`Good to see you, ${profile.firstName}`}
        description="A summary of your accounts, borrowing and recent activity."
      />

      <section aria-label="Summary" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile
          label="Total balance"
          value={formatCurrency(totalBalance, currency)}
          hint={`${accounts.length} ${accounts.length === 1 ? "account" : "accounts"}`}
          tone={totalBalance < 0 ? "critical" : "neutral"}
        />
        <StatTile
          label="Loans outstanding"
          value={formatCurrency(loanOutstanding, currency)}
          hint={`${activeLoans.length} active`}
        />
        <StatTile
          label="Card balance"
          value={formatCurrency(cardBalance, currency)}
          hint={`${cards.length} ${cards.length === 1 ? "card" : "cards"}`}
        />
        <StatTile
          label="Unread alerts"
          value={String(notifications.unreadCount)}
          hint={notifications.unreadCount === 0 ? "You are all caught up" : "Needs attention"}
          tone={notifications.unreadCount > 0 ? "critical" : "neutral"}
        />
      </section>

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          {/* Balance history — chart only when the data supports one. */}
          <Card>
            <CardHeader
              title="Balance history"
              description={
                primaryAccount
                  ? `Account ${maskAccountNumber(primaryAccount.accountNumber)}`
                  : undefined
              }
            />
            <CardBody>
              {points.length >= MIN_POINTS_FOR_CHART ? (
                <BalanceTrendChart points={points} currency={currency} />
              ) : (
                <EmptyState
                  title="Not enough history to chart yet"
                  description={
                    primaryAccount
                      ? `This account has ${points.length} recorded ${points.length === 1 ? "transaction" : "transactions"}. A trend line needs at least ${MIN_POINTS_FOR_CHART} to mean anything.`
                      : "Open an account to start building a transaction history."
                  }
                />
              )}
            </CardBody>
          </Card>

          <Card>
            <CardHeader
              title="Recent transactions"
              action={
                <Link href="/transactions" className="text-sm font-medium text-accent hover:underline">
                  View all
                </Link>
              }
            />
            {recent.length === 0 ? (
              <EmptyState
                title="No transactions yet"
                description="Deposits, withdrawals and transfers will appear here."
              />
            ) : (
              <ul className="divide-y divide-line">
                {recent.slice(0, 6).map((t) => (
                  <li key={t.transactionRef} className="flex items-center justify-between gap-4 px-5 py-3">
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium text-ink">
                        {t.description || humanise(t.type)}
                      </p>
                      <p className="text-xs text-ink-subtle">{formatDate(t.createdAt)}</p>
                    </div>
                    <span
                      className={`tabular shrink-0 text-sm font-medium ${
                        isCredit(t.type) ? "text-positive" : "text-ink"
                      }`}
                    >
                      {isCredit(t.type) ? "+" : "−"}
                      {formatCurrency(Math.abs(t.amount), t.currency)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>

        <div className="space-y-6">
          <Card>
            <CardHeader title="Verification" />
            <CardBody className="space-y-3">
              <div className="flex items-center justify-between gap-3">
                <span className="text-sm text-ink-muted">KYC status</span>
                <Badge tone={statusTone(profile.kycStatus)}>{humanise(profile.kycStatus)}</Badge>
              </div>
              <div className="flex items-center justify-between gap-3">
                <span className="text-sm text-ink-muted">Two-factor</span>
                <Badge tone={profile.twoFactorEnabled ? "positive" : "caution"}>
                  {profile.twoFactorEnabled ? "Enabled" : "Not enabled"}
                </Badge>
              </div>
              <div className="flex items-center justify-between gap-3">
                <span className="text-sm text-ink-muted">Credit score</span>
                <span className="tabular text-sm font-medium text-ink">
                  {profile.creditScore || "—"}
                </span>
              </div>
              <Link
                href="/profile"
                className="block pt-1 text-sm font-medium text-accent hover:underline"
              >
                Manage security
              </Link>
            </CardBody>
          </Card>

          <Card>
            <CardHeader title="Accounts" />
            {accounts.length === 0 ? (
              <EmptyState title="No accounts yet" />
            ) : (
              <ul className="divide-y divide-line">
                {accounts.slice(0, 4).map((a) => (
                  <li key={a.id} className="px-5 py-3">
                    <Link href={`/accounts/${a.id}`} className="group block">
                      <div className="flex items-center justify-between gap-3">
                        <span className="text-sm font-medium text-ink group-hover:text-accent">
                          {humanise(a.accountType)}
                        </span>
                        <Badge tone={statusTone(a.status)}>{humanise(a.status)}</Badge>
                      </div>
                      <div className="mt-1 flex items-center justify-between gap-3">
                        <span className="text-xs text-ink-subtle">
                          {maskAccountNumber(a.accountNumber)}
                        </span>
                        <span className="tabular text-sm text-ink">
                          {formatCurrency(a.balance, a.currency)}
                        </span>
                      </div>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Card>

          {cards.length > 0 ? (
            <Card>
              <CardHeader title="Cards" />
              <ul className="divide-y divide-line">
                {cards.slice(0, 3).map((c) => (
                  <li key={c.id} className="px-5 py-3">
                    <Link href={`/cards/${c.id}`} className="group block">
                      <div className="flex items-center justify-between gap-3">
                        <span className="text-sm font-medium text-ink group-hover:text-accent">
                          {humanise(c.cardType)}
                        </span>
                        <span className="tabular text-xs text-ink-subtle">
                          {c.maskedCardNumber}
                        </span>
                      </div>
                      <p className="mt-1 text-xs text-ink-subtle">
                        {formatCurrency(c.availableCredit, c.currency)} available
                      </p>
                    </Link>
                  </li>
                ))}
              </ul>
            </Card>
          ) : null}

          {stats ? (
            <Card>
              <CardHeader title="Your activity" />
              <CardBody className="space-y-2 text-sm">
                <div className="flex justify-between">
                  <span className="text-ink-muted">Transactions</span>
                  <span className="tabular text-ink">{stats.totalTransactions}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-ink-muted">Money in</span>
                  <span className="tabular text-positive">
                    {formatCurrency(stats.totalAmountIn, currency)}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-ink-muted">Money out</span>
                  <span className="tabular text-ink">
                    {formatCurrency(stats.totalAmountOut, currency)}
                  </span>
                </div>
              </CardBody>
            </Card>
          ) : null}
        </div>
      </div>
    </>
  );
}
