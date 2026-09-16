import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { requireSession } from "@/lib/session";
import { getCardStatements, getCardTransactions, getCreditCard } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
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
  formatDate,
  formatDateTime,
  formatPercent,
  humanise,
} from "@/lib/format";

export const metadata: Metadata = { title: "Credit card" };

export default async function CardDetailPage({ params }: { params: Promise<{ id: string }> }) {
  await requireSession();
  const { id } = await params;
  const cardId = Number(id);
  if (!Number.isFinite(cardId)) notFound();

  let card;
  let txPage;
  let statements;
  try {
    card = await getCreditCard(cardId);
    [txPage, statements] = await Promise.all([
      getCardTransactions(cardId, 0, 25),
      getCardStatements(cardId),
    ]);
  } catch (error) {
    if (error instanceof ApiError && error.isNotFound) notFound();
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Credit card" />
          <ErrorState message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  const transactions = txPage?.content ?? [];

  return (
    <>
      <PageHeader
        title={`${humanise(card.cardType)} card`}
        description={card.maskedCardNumber}
        action={
          <Link href="/cards" className="text-sm font-medium text-accent hover:underline">
            Back to cards
          </Link>
        }
      />

      <Badge tone={statusTone(card.status)}>{humanise(card.status)}</Badge>

      <section aria-label="Card summary" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile label="Current balance" value={formatCurrency(card.currentBalance, card.currency)} />
        <StatTile
          label="Available credit"
          value={formatCurrency(card.availableCredit, card.currency)}
          hint={`Limit ${formatCurrency(card.creditLimit, card.currency)}`}
        />
        <StatTile
          label="Minimum due"
          value={formatCurrency(card.minimumPaymentDue, card.currency)}
          hint={card.paymentDueDate ? `By ${formatDate(card.paymentDueDate)}` : undefined}
        />
        <StatTile label="Rewards" value={`${card.rewardsPoints} pts`} hint={`APR ${formatPercent(card.apr)}`} />
      </section>

      <Card>
        <CardHeader title="Card transactions" />
        {transactions.length === 0 ? (
          <EmptyState title="No card transactions yet" />
        ) : (
          <TableShell label="Card transactions">
            <thead>
              <tr>
                <Th>Date</Th>
                <Th>Merchant</Th>
                <Th>Category</Th>
                <Th>Type</Th>
                <Th align="right">Amount</Th>
              </tr>
            </thead>
            <tbody>
              {transactions.map((t) => (
                <tr key={t.transactionRef} className="hover:bg-sunken">
                  <Td>{formatDateTime(t.createdAt)}</Td>
                  <Td>{t.merchantName || t.description || "—"}</Td>
                  <Td>{t.merchantCategory || "—"}</Td>
                  <Td>{humanise(t.type)}</Td>
                  <Td align="right">{formatCurrency(t.amount, card.currency)}</Td>
                </tr>
              ))}
            </tbody>
          </TableShell>
        )}
      </Card>

      <Card>
        <CardHeader title="Statements" />
        {statements.length === 0 ? (
          <EmptyState
            title="No statements yet"
            description="Statements are generated monthly on the billing cycle date."
          />
        ) : (
          <TableShell label="Card statements">
            <thead>
              <tr>
                <Th>Statement date</Th>
                <Th align="right">Opening</Th>
                <Th align="right">Closing</Th>
                <Th align="right">Purchases</Th>
                <Th align="right">Payments</Th>
                <Th align="right">Interest</Th>
                <Th>Due</Th>
                <Th>Settled</Th>
              </tr>
            </thead>
            <tbody>
              {statements.map((s) => (
                <tr key={s.id} className="hover:bg-sunken">
                  <Td>{formatDate(s.statementDate)}</Td>
                  <Td align="right">{formatCurrency(s.openingBalance, card.currency)}</Td>
                  <Td align="right">{formatCurrency(s.closingBalance, card.currency)}</Td>
                  <Td align="right">{formatCurrency(s.totalPurchases, card.currency)}</Td>
                  <Td align="right">{formatCurrency(s.totalPayments, card.currency)}</Td>
                  <Td align="right">{formatCurrency(s.interestCharged, card.currency)}</Td>
                  <Td>{formatDate(s.paymentDueDate)}</Td>
                  <Td>
                    <Badge tone={s.paidInFull ? "positive" : "caution"}>
                      {s.paidInFull ? "Paid in full" : "Outstanding"}
                    </Badge>
                  </Td>
                </tr>
              ))}
            </tbody>
          </TableShell>
        )}
      </Card>
    </>
  );
}
