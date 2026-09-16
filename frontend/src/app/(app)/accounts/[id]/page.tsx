import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { requireSession } from "@/lib/session";
import { getAccount, getTransactions } from "@/lib/api/banking";
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
  TableShell,
  Td,
  Th,
  statusTone,
} from "@/components/ui/primitives";
import {
  formatCurrency,
  formatDateTime,
  formatPercent,
  humanise,
  isCredit,
  maskAccountNumber,
} from "@/lib/format";

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
          <ErrorState message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  const transactions = page?.content ?? [];

  return (
    <>
      <PageHeader
        title={`${humanise(account.accountType)} account`}
        description={maskAccountNumber(account.accountNumber)}
        action={
          <Link href="/accounts" className="text-sm font-medium text-accent hover:underline">
            Back to accounts
          </Link>
        }
      />

      <div className="flex items-center gap-3">
        <Badge tone={statusTone(account.status)}>{humanise(account.status)}</Badge>
        {account.status === "OVERDRAWN" ? (
          <span className="text-sm text-critical">
            This account is overdrawn. Deposits repay the overdraft first.
          </span>
        ) : null}
      </div>

      <section aria-label="Balances" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile
          label="Balance"
          value={formatCurrency(account.balance, account.currency)}
          tone={account.balance < 0 ? "critical" : "neutral"}
        />
        <StatTile
          label="Available"
          value={formatCurrency(account.availableBalance, account.currency)}
          hint="Includes overdraft head-room"
        />
        <StatTile
          label="Overdraft limit"
          value={formatCurrency(account.overdraftLimit, account.currency)}
          hint={
            account.overdraftBalance > 0
              ? `${formatCurrency(account.overdraftBalance, account.currency)} in use`
              : "None in use"
          }
        />
        <StatTile label="Interest rate" value={formatPercent(account.interestRate * 100)} />
      </section>

      <Card>
        <CardHeader
          title="Transactions"
          description={
            page ? `${page.totalElements} recorded on this account` : undefined
          }
          action={
            <Link href="/transactions" className="text-sm font-medium text-accent hover:underline">
              Move money
            </Link>
          }
        />
        {transactions.length === 0 ? (
          <EmptyState
            title="No transactions on this account"
            description="Deposits, withdrawals and transfers will be listed here."
          />
        ) : (
          <TableShell label="Account transactions">
            <thead>
              <tr>
                <Th>Date</Th>
                <Th>Description</Th>
                <Th>Type</Th>
                <Th>Reference</Th>
                <Th align="right">Amount</Th>
                <Th align="right">Balance after</Th>
              </tr>
            </thead>
            <tbody>
              {transactions.map((t) => (
                <tr key={t.transactionRef} className="hover:bg-sunken">
                  <Td>{formatDateTime(t.createdAt)}</Td>
                  <Td>{t.description || "—"}</Td>
                  <Td>{humanise(t.type)}</Td>
                  <Td className="font-mono text-xs text-ink-subtle">{t.transactionRef}</Td>
                  <Td align="right" className={isCredit(t.type) ? "text-positive" : undefined}>
                    {isCredit(t.type) ? "+" : "−"}
                    {formatCurrency(Math.abs(t.amount), t.currency)}
                  </Td>
                  <Td align="right">{formatCurrency(t.balanceAfter, t.currency)}</Td>
                </tr>
              ))}
            </tbody>
          </TableShell>
        )}
        {page && page.totalPages > 1 ? (
          <CardBody className="border-t border-line text-xs text-ink-subtle">
            Showing the {transactions.length} most recent of {page.totalElements} transactions.
          </CardBody>
        ) : null}
      </Card>
    </>
  );
}
