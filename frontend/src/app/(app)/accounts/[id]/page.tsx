import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { requireSession } from "@/lib/session";
import { getAccount, getTransactions } from "@/lib/api/banking";
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
  statusTone,
} from "@/components/ui/primitives";
import { formatCurrency, formatDate, formatPercent, humanise, maskAccountNumber } from "@/lib/format";
import { TransactionRow } from "@/features/transactions/TransactionRow";

export const metadata: Metadata = { title: "Account" };

export default async function AccountDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  await requireSession();
  const { id } = await params;
  const accountId = Number(id);
  if (!Number.isFinite(accountId)) notFound();

  let account;
  let page;
  try {
    account = await getAccount(accountId);
    page = await getTransactions(accountId, 0, 25);
  } catch (error) {
    if (error instanceof ApiError && error.isNotFound) notFound();
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Account" />
          <ErrorState title="We could not load this account" message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  const transactions = page?.content ?? [];
  const overdrawn = account.balance < 0;

  return (
    <>
      <Link
        href="/accounts"
        className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
      >
        <ArrowLeft aria-hidden="true" className="h-4 w-4" />
        Back to accounts
      </Link>

      {/*
       * The balance is the headline, because it is the reason the page was
       * opened. The account number stays masked here as it is everywhere else:
       * this page is no more private than the list that links to it.
       */}
      <section
        aria-labelledby="account-heading"
        className="rounded-[var(--radius-card)] border border-line bg-surface p-6 sm:p-7"
      >
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <h1 id="account-heading" className="text-xl font-semibold tracking-tight text-ink">
              {humanise(account.accountType)} account
            </h1>
            <p className="tabular mt-1 text-sm text-ink-subtle">
              {maskAccountNumber(account.accountNumber)}
            </p>
          </div>
          <Badge tone={statusTone(account.status)}>{humanise(account.status)}</Badge>
        </div>

        <div className="mt-6 flex flex-wrap items-end gap-x-10 gap-y-4">
          <div>
            <p className="text-xs uppercase tracking-wide text-ink-subtle">Balance</p>
            <Money
              amount={account.balance}
              currency={account.currency}
              size="lg"
              className={`mt-1 block ${overdrawn ? "text-critical" : "text-ink"}`}
            />
          </div>
          <div>
            <p className="text-xs uppercase tracking-wide text-ink-subtle">Available</p>
            <Money
              amount={account.availableBalance}
              currency={account.currency}
              size="lg"
              className="mt-1 block text-ink"
            />
          </div>
        </div>

        {account.status === "OVERDRAWN" ? (
          <p className="mt-5 rounded-[var(--radius-control)] bg-critical-soft px-4 py-3 text-sm text-critical">
            This account is overdrawn. Deposits repay the overdraft first.
          </p>
        ) : null}
      </section>

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="min-w-0 lg:col-span-2">
          <Card>
            <CardHeader
              title="Transactions"
              description={page ? `${page.totalElements} recorded on this account` : undefined}
              action={
                <Link
                  href="/transactions"
                  className="text-sm font-medium text-primary hover:underline"
                >
                  Move money
                </Link>
              }
            />
            {page === null ? (
              <CardBody>
                {/* A failed request, not an account with nothing on it. */}
                <ErrorState
                  title="We could not load this account's transactions"
                  message="The transaction service did not answer. The balance above is unaffected."
                />
              </CardBody>
            ) : transactions.length === 0 ? (
              <EmptyState
                title="No transactions on this account"
                description="Deposits, withdrawals and transfers will be listed here."
              />
            ) : (
              <ul className="divide-y divide-line">
                {transactions.map((t) => (
                  <TransactionRow key={t.transactionRef} transaction={t} showBalanceAfter />
                ))}
              </ul>
            )}
            {page && page.totalPages > 1 ? (
              <CardBody className="border-t border-line text-xs text-ink-subtle">
                Showing the {transactions.length} most recent of {page.totalElements} transactions.
              </CardBody>
            ) : null}
          </Card>
        </div>

        <Card className="h-fit">
          <CardHeader title="Account information" />
          <CardBody>
            <DetailList columns={1}>
              <Detail label="Account number">
                <span className="tabular">{maskAccountNumber(account.accountNumber)}</span>
              </Detail>
              <Detail label="Type">{humanise(account.accountType)}</Detail>
              <Detail label="Currency">{account.currency}</Detail>
              <Detail label="Interest rate">{formatPercent(account.interestRate * 100)}</Detail>
              <Detail label="Overdraft limit">
                <span className="tabular">
                  {formatCurrency(account.overdraftLimit, account.currency)}
                </span>
                {account.overdraftBalance > 0 ? (
                  <span className="block text-xs text-critical">
                    {formatCurrency(account.overdraftBalance, account.currency)} in use
                  </span>
                ) : null}
              </Detail>
              <Detail label="Opened">{formatDate(account.createdAt)}</Detail>
            </DetailList>
          </CardBody>
        </Card>
      </div>
    </>
  );
}
