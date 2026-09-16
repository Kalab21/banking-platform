import type { Metadata } from "next";
import { requireSession } from "@/lib/session";
import { getAccounts, getTransactions } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  PageHeader,
  TableShell,
  Td,
  Th,
} from "@/components/ui/primitives";
import { formatCurrency, formatDateTime, humanise, isCredit, maskAccountNumber } from "@/lib/format";
import { MoneyForms } from "@/features/transactions/MoneyForms";
import type { Transaction } from "@/types/api";

export const metadata: Metadata = { title: "Transactions" };

export default async function TransactionsPage() {
  const session = await requireSession();

  let accounts;
  try {
    accounts = await getAccounts(session.userId);
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Transactions" />
          <ErrorState message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  // Combine the recent history of every account into one timeline.
  const pages = await Promise.all(accounts.map((a) => getTransactions(a.id, 0, 20)));
  const accountNumbers = new Map(accounts.map((a) => [a.id, a.accountNumber]));
  const all: Transaction[] = pages
    .flatMap((p) => p?.content ?? [])
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());

  return (
    <>
      <PageHeader
        title="Transactions"
        description="Move money between accounts and review everything that has settled."
      />

      <MoneyForms accounts={accounts} />

      <Card>
        <CardHeader title="Activity" description="Most recent first, across all your accounts." />
        {all.length === 0 ? (
          <EmptyState
            title="Nothing has moved yet"
            description="Once you deposit or transfer, the record will appear here."
          />
        ) : (
          <TableShell label="All transactions">
            <thead>
              <tr>
                <Th>Date</Th>
                <Th>Account</Th>
                <Th>Description</Th>
                <Th>Type</Th>
                <Th align="right">Amount</Th>
                <Th align="right">Balance after</Th>
              </tr>
            </thead>
            <tbody>
              {all.slice(0, 50).map((t) => (
                <tr key={`${t.accountId}-${t.transactionRef}`} className="hover:bg-sunken">
                  <Td>{formatDateTime(t.createdAt)}</Td>
                  <Td>{maskAccountNumber(accountNumbers.get(t.accountId))}</Td>
                  <Td>{t.description || "—"}</Td>
                  <Td>{humanise(t.type)}</Td>
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
      </Card>
    </>
  );
}
