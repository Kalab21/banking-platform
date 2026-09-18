import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight, HandCoins, Landmark, ReceiptText, ShieldCheck } from "lucide-react";
import { requireSession } from "@/lib/session";
import { getCurrentUser } from "@/lib/current-user";
import {
  getAccounts,
  getCreditCards,
  getLoans,
  getNotifications,
  getTransactions,
} from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
  CardBody,
  CardHeader,
  Detail,
  DetailList,
  EmptyState,
  ErrorState,
  Money,
  PageHeader,
  ProgressBar,
  statusTone,
} from "@/components/ui/primitives";
import { formatCurrency, humanise, maskAccountNumber } from "@/lib/format";
import { AccountCard } from "@/features/accounts/AccountCard";
import { TransactionRow } from "@/features/transactions/TransactionRow";
import { BalanceTrendChart, type BalancePoint } from "@/features/dashboard/BalanceTrendChart";

export const metadata: Metadata = { title: "Overview" };

/** Below this many transactions a line chart would imply a trend that isn't there. */
const MIN_POINTS_FOR_CHART = 4;

/**
 * Every destination here is a route that exists today. Deliberately no
 * Transfer, Deposit or Withdraw tiles: those belong to the money-movement work
 * and pointing at the current forms with new buttons would advertise a flow
 * that is about to be replaced.
 */
const QUICK_ACTIONS = [
  { href: "/accounts", label: "Accounts", icon: Landmark },
  { href: "/transactions", label: "Move money", icon: ReceiptText },
  { href: "/payments", label: "Payments", icon: HandCoins },
  { href: "/profile", label: "Security", icon: ShieldCheck },
];

export default async function DashboardPage() {
  const session = await requireSession();

  let data;
  try {
    const [profile, accounts, loans, cards, notifications] = await Promise.all([
      getCurrentUser(session.userId),
      getAccounts(session.userId),
      getLoans(session.userId),
      getCreditCards(session.userId),
      getNotifications(session.userId, 0, 5),
    ]);
    data = { profile, accounts, loans, cards, notifications };
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      /*
       * A failure is reported as a failure. Rendering zeroes here would tell
       * the customer their accounts are empty, which is a different and much
       * worse statement than "we could not load them".
       */
      return (
        <>
          <PageHeader title="Overview" />
          <ErrorState title="We could not load your dashboard" message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  const { profile, accounts, loans, cards, notifications } = data;

  const totalBalance = accounts.reduce((sum, a) => sum + a.balance, 0);
  const activeLoans = loans.filter((l) => l.status === "ACTIVE");
  const loanOutstanding = activeLoans.reduce((sum, l) => sum + l.remainingBalance, 0);
  const cardBalance = cards.reduce((sum, c) => sum + c.currentBalance, 0);
  const cardLimit = cards.reduce((sum, c) => sum + c.creditLimit, 0);
  const currency = accounts[0]?.currency ?? "USD";

  /*
   * The trend is drawn from one account's real recorded balances. Pick whichever
   * account actually has history rather than whichever happens to be first — a
   * chart of an untouched savings account says nothing. One page of history per
   * account, which is the same number of requests the page made before.
   */
  const histories = await Promise.all(
    accounts.map(async (account) => ({ account, page: await getTransactions(account.id, 0, 12) })),
  );

  /*
   * A null page means the request did not succeed; a page with no content
   * means the account has no transactions. Those are different facts and the
   * panels below say different things about them — "this account has no
   * transactions" is a claim, and it must not be made on the strength of a
   * gateway timeout.
   */
  const historyUnavailable = accounts.length > 0 && histories.every((h) => h.page === null);
  const loaded = histories.filter((h) => h.page !== null);

  const busiest = loaded.reduce<(typeof loaded)[number] | undefined>(
    (best, current) =>
      best === undefined || (current.page?.content.length ?? 0) > (best.page?.content.length ?? 0)
        ? current
        : best,
    undefined,
  );

  const primaryAccount = busiest?.account;
  const recent = busiest?.page?.content ?? [];
  const points: BalancePoint[] = [...recent]
    .reverse()
    .map((t) => ({ at: t.createdAt, balance: t.balanceAfter }));

  const accountNumbers = new Map(accounts.map((a) => [a.id, a.accountNumber]));

  return (
    <>
      <PageHeader
        title={`Welcome back, ${profile.firstName}`}
        description="Here is where your money stands today."
      />

      {/* ------------------------------------------------------------- hero */}
      <section
        aria-labelledby="total-balance-label"
        className="overflow-hidden rounded-[var(--radius-card)] bg-navy text-white"
      >
        <div className="relative px-6 py-7 sm:px-8 sm:py-8">
          <div
            aria-hidden="true"
            className="pointer-events-none absolute -right-20 -top-24 h-64 w-64 rounded-full bg-primary/25 blur-3xl"
          />
          <div className="relative flex flex-wrap items-end justify-between gap-6">
            <div>
              <p
                id="total-balance-label"
                className="text-xs font-medium uppercase tracking-wider text-white/60"
              >
                Total balance
              </p>
              <Money amount={totalBalance} currency={currency} size="hero" className="mt-2 block" />
              <p className="mt-2 text-sm text-white/65">
                {accounts.length === 0
                  ? "No accounts open yet"
                  : `Across ${accounts.length} ${accounts.length === 1 ? "account" : "accounts"}`}
              </p>
            </div>

            <nav aria-label="Quick actions" className="flex flex-wrap gap-2">
              {QUICK_ACTIONS.map(({ href, label, icon: Icon }) => (
                <Link
                  key={href}
                  href={href}
                  className="inline-flex min-h-11 items-center gap-2 rounded-[var(--radius-control)] bg-white/10 px-4 text-sm font-medium text-white ring-1 ring-inset ring-white/15 transition-colors hover:bg-white/15"
                >
                  <Icon aria-hidden="true" className="h-4 w-4" />
                  {label}
                </Link>
              ))}
            </nav>
          </div>
        </div>
      </section>

      {/* --------------------------------------------------------- accounts */}
      <section aria-labelledby="accounts-heading" className="space-y-3">
        <div className="flex items-end justify-between gap-4">
          <h2 id="accounts-heading" className="text-base font-semibold text-ink">
            Your accounts
          </h2>
          {accounts.length > 0 ? (
            <Link
              href="/accounts"
              className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
            >
              View all
              <ArrowRight aria-hidden="true" className="h-3.5 w-3.5" />
            </Link>
          ) : null}
        </div>

        {accounts.length === 0 ? (
          <Card>
            <EmptyState
              title="No accounts yet"
              description="Once an account application is approved, it will appear here with its balance and activity."
            />
          </Card>
        ) : (
          /*
           * The track count follows the number of cards. A fixed three-column
           * grid holding two accounts leaves a third of the row empty, which
           * reads as something failing to load.
           */
          <div
            className={`grid gap-4 sm:grid-cols-2 ${
              accounts.length >= 3 ? "xl:grid-cols-3" : ""
            }`}
          >
            {accounts.slice(0, 3).map((account) => (
              <AccountCard key={account.id} account={account} />
            ))}
          </div>
        )}
      </section>

      {/* ----------------------------------------------- history + activity */}
      {/*
       * `min-w-0` on the columns, not decoration: a grid item defaults to
       * `min-width: auto` and the chart inside reports an intrinsic minimum
       * width, so without it the column refuses to narrow and the dashboard
       * grows a horizontal scrollbar on a phone.
       */}
      <div className="grid gap-6 lg:grid-cols-5">
        <div className="min-w-0 lg:col-span-3">
          <Card className="h-full">
            <CardHeader
              title="Balance history"
              description={
                primaryAccount
                  ? `${humanise(primaryAccount.accountType)} account ${maskAccountNumber(primaryAccount.accountNumber)}`
                  : undefined
              }
            />
            <CardBody>
              {historyUnavailable ? (
                <ErrorState
                  title="We could not load your balance history"
                  message="The transaction service did not answer. Your balances above are unaffected."
                />
              ) : points.length >= MIN_POINTS_FOR_CHART ? (
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
        </div>

        <div className="min-w-0 lg:col-span-2">
          <Card className="h-full">
            <CardHeader
              title="Recent activity"
              action={
                <Link
                  href="/transactions"
                  className="text-sm font-medium text-primary hover:underline"
                >
                  View all
                </Link>
              }
            />
            {historyUnavailable ? (
              <CardBody>
                <ErrorState
                  title="We could not load your recent activity"
                  message="The transaction service did not answer. Try again shortly."
                />
              </CardBody>
            ) : recent.length === 0 ? (
              <EmptyState
                title="No transactions yet"
                description="Deposits, withdrawals and transfers will appear here."
              />
            ) : (
              <ul className="divide-y divide-line">
                {recent.slice(0, 5).map((t) => (
                  <TransactionRow
                    key={t.transactionRef}
                    transaction={t}
                    accountNumber={accountNumbers.get(t.accountId)}
                  />
                ))}
              </ul>
            )}
          </Card>
        </div>
      </div>

      {/* --------------------------------------------- products + security */}
      <div className="grid gap-6 lg:grid-cols-3">
        <Card>
          <CardHeader
            title="Credit cards"
            action={
              cards.length > 0 ? (
                <Link href="/cards" className="text-sm font-medium text-primary hover:underline">
                  Manage
                </Link>
              ) : undefined
            }
          />
          {cards.length === 0 ? (
            <EmptyState title="No credit cards" description="Approved card applications appear here." />
          ) : (
            <CardBody className="space-y-4">
              <div>
                <p className="text-xs uppercase tracking-wide text-ink-subtle">Balance</p>
                <Money amount={cardBalance} currency={currency} size="lg" className="mt-1 block" />
              </div>
              {cardLimit > 0 ? (
                <div>
                  <ProgressBar
                    value={(cardBalance / cardLimit) * 100}
                    label={`${formatCurrency(cardBalance, currency)} of a ${formatCurrency(cardLimit, currency)} combined limit used`}
                  />
                  <p className="mt-2 text-xs text-ink-subtle">
                    {formatCurrency(Math.max(cardLimit - cardBalance, 0), currency)} available of{" "}
                    {formatCurrency(cardLimit, currency)}
                  </p>
                </div>
              ) : null}
              <p className="text-xs text-ink-subtle">
                {cards.length} {cards.length === 1 ? "card" : "cards"}
              </p>
            </CardBody>
          )}
        </Card>

        <Card>
          <CardHeader
            title="Loans"
            action={
              loans.length > 0 ? (
                <Link href="/loans" className="text-sm font-medium text-primary hover:underline">
                  Manage
                </Link>
              ) : undefined
            }
          />
          {activeLoans.length === 0 ? (
            <EmptyState
              title="No active loans"
              description="Approved loans appear here once disbursed."
            />
          ) : (
            <CardBody className="space-y-4">
              <div>
                <p className="text-xs uppercase tracking-wide text-ink-subtle">Outstanding</p>
                <Money
                  amount={loanOutstanding}
                  currency={currency}
                  size="lg"
                  className="mt-1 block"
                />
              </div>
              <DetailList columns={1} className="gap-y-2">
                {activeLoans.slice(0, 2).map((loan) => (
                  <Detail key={loan.id} label={humanise(loan.loanType)}>
                    <span className="tabular">
                      {formatCurrency(loan.monthlyPayment, loan.currency)} monthly
                    </span>
                  </Detail>
                ))}
              </DetailList>
            </CardBody>
          )}
        </Card>

        {/*
         * Security is a panel, not a headline. Identity and KYC state matter,
         * but a customer opening their bank wants their balance first — and
         * "SUBMITTED" is not "verified", so the wording says what is true.
         */}
        <Card>
          <CardHeader
            title="Security & identity"
            action={
              <Link href="/profile" className="text-sm font-medium text-primary hover:underline">
                Manage
              </Link>
            }
          />
          <CardBody className="space-y-3">
            <div className="flex items-center justify-between gap-3">
              <span className="text-sm text-ink-muted">Identity</span>
              <Badge tone={profile.identityStatus === "SUBMITTED" ? "caution" : "neutral"}>
                {profile.identityStatus === "SUBMITTED" ? "Submitted" : "Not submitted"}
              </Badge>
            </div>
            <div className="flex items-center justify-between gap-3">
              <span className="text-sm text-ink-muted">Two-factor</span>
              <Badge tone={profile.twoFactorEnabled ? "positive" : "caution"}>
                {profile.twoFactorEnabled ? "Enabled" : "Not enabled"}
              </Badge>
            </div>
            <div className="flex items-center justify-between gap-3">
              <span className="text-sm text-ink-muted">Know-your-customer</span>
              <Badge tone={statusTone(profile.kycStatus)}>{humanise(profile.kycStatus)}</Badge>
            </div>
            <div className="flex items-center justify-between gap-3">
              <span className="text-sm text-ink-muted">Unread alerts</span>
              <Link href="/notifications" className="text-sm font-medium text-primary hover:underline">
                {notifications.unreadCount}
                <span className="sr-only"> unread notifications</span>
              </Link>
            </div>
          </CardBody>
        </Card>
      </div>

      {cards.length > 0 ? (
        <p className="sr-only">
          Card and loan figures are summaries. Open the card or loan pages for the full detail.
        </p>
      ) : null}
    </>
  );
}
