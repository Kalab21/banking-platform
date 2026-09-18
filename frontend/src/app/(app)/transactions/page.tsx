import type { Metadata } from "next";
import { requireSession } from "@/lib/session";
import { getAccounts, getTransactions } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import { Card, CardHeader, EmptyState, ErrorState, PageHeader } from "@/components/ui/primitives";
import { MoneyForms } from "@/features/transactions/MoneyForms";
import { TransactionRow } from "@/features/transactions/TransactionRow";
import type { Transaction } from "@/types/api";

export const metadata: Metadata = { title: "Transactions" };

/** How many lines of combined history to render before it stops being a list. */
const MAX_ROWS = 50;

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
          <ErrorState title="We could not load your transactions" message={error.userMessage} />
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
        description="Move money between your accounts and review everything that has settled."
      />

      {/*
       * The forms are untouched: their idempotency keys, their unknown-outcome
       * handling and the semantics of each request are the money-movement
       * contract, and this pass is about how the page reads, not what it does.
       */}
      <MoneyForms accounts={accounts} />

      <Card>
        <CardHeader
          title="Activity"
          description={
            all.length > 0
              ? `Most recent first, across ${accounts.length} ${accounts.length === 1 ? "account" : "accounts"}.`
              : undefined
          }
        />
        {all.length === 0 ? (
          <EmptyState
            title="Nothing has moved yet"
            description="Once you deposit or transfer, the record will appear here."
          />
        ) : (
          /*
           * Rows rather than a six-column table. The table version needed a
           * horizontal scrollbar on any phone, which is not a way to read a
           * bank statement.
           */
          <ul className="divide-y divide-line">
            {all.slice(0, MAX_ROWS).map((t) => (
              <TransactionRow
                key={`${t.accountId}-${t.transactionRef}`}
                transaction={t}
                accountNumber={accountNumbers.get(t.accountId)}
                showBalanceAfter
              />
            ))}
          </ul>
        )}
      </Card>
    </>
  );
}
