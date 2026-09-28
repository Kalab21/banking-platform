import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { requireSession } from "@/lib/session";
import { getAccounts, getCardStatements, getCardTransactions, getCreditCard } from "@/lib/api/banking";
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
  TableShell,
  Td,
  Th,
  statusTone,
} from "@/components/ui/primitives";
import {
  formatCurrency,
  formatDate,
  formatDateTime,
  formatNumber,
  formatPercent,
  humanise,
  isCardCredit,
} from "@/lib/format";
import { VirtualCard } from "@/features/cards/VirtualCard";
import { FreezeControl } from "@/features/cards/FreezeControl";
import { utilisation } from "@/features/cards/utilisation";
import { ServicingPayment } from "@/features/servicing/ServicingPayment";
import { payCardAction } from "@/features/servicing/actions";
import { cardPaymentChoices, servicingAccounts } from "@/features/servicing/choices";
import { toMoneyAccountOptions } from "@/features/transactions/money-account";

export const metadata: Metadata = { title: "Credit card" };

export default async function CardDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const session = await requireSession();
  const { id } = await params;
  const cardId = Number(id);
  if (!Number.isFinite(cardId)) notFound();

  let card;
  let txPage;
  let statements;
  let accounts;
  try {
    card = await getCreditCard(cardId);
    [txPage, statements, accounts] = await Promise.all([
      getCardTransactions(cardId, 0, 25),
      getCardStatements(cardId),
      getAccounts(session.userId),
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

  const used = utilisation(card.currentBalance, card.creditLimit);

  return (
    <>
      <Link
        href="/cards"
        className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
      >
        <ArrowLeft aria-hidden="true" className="h-4 w-4" />
        Back to cards
      </Link>

      <section
        aria-labelledby="card-heading"
        className="grid gap-6 rounded-[var(--radius-card)] border border-line bg-surface p-5 sm:p-6 lg:grid-cols-[minmax(0,20rem)_1fr] lg:items-start"
      >
        <VirtualCard card={card} />

        <div className="min-w-0 space-y-5">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h1 id="card-heading" className="text-xl font-semibold tracking-tight text-ink">
                {humanise(card.cardType)} card
              </h1>
              <p className="tabular mt-0.5 text-sm text-ink-subtle">{card.maskedCardNumber}</p>
            </div>
            <Badge tone={statusTone(card.status)}>{humanise(card.status)}</Badge>
          </div>

          <div>
            <div className="flex flex-wrap items-end justify-between gap-2">
              <div>
                <p className="text-xs uppercase tracking-wide text-ink-subtle">Current balance</p>
                <Money
                  amount={card.currentBalance}
                  currency={card.currency}
                  size="lg"
                  className="mt-1 block text-ink"
                />
              </div>
              <p className="text-sm text-ink-muted">
                <span className="tabular font-medium text-ink">
                  {formatCurrency(card.availableCredit, card.currency)}
                </span>{" "}
                available
              </p>
            </div>
            <div className="mt-3">
              <ProgressBar
                value={used.percent}
                tone={used.tone}
                label={`${used.percent}% of a ${formatCurrency(card.creditLimit, card.currency)} credit limit used`}
              />
              <p className="mt-2 text-xs text-ink-subtle">
                {used.percent}% of {formatCurrency(card.creditLimit, card.currency)} limit used
              </p>
            </div>
          </div>

          {/*
           * Only fields the card record actually carries. No expiry, no CVV and
           * no cardholder name: the API has none of them, and a control or a
           * value that looks real but is invented is worse than its absence.
           */}
          <DetailList columns={3} className="border-t border-line pt-4">
            <Detail label="Credit limit">
              <span className="tabular">{formatCurrency(card.creditLimit, card.currency)}</span>
            </Detail>
            <Detail label="Statement balance">
              <span className="tabular">{formatCurrency(card.statementBalance, card.currency)}</span>
            </Detail>
            <Detail label="Minimum due">
              <span className="tabular">
                {formatCurrency(card.minimumPaymentDue, card.currency)}
              </span>
            </Detail>
            <Detail label="Payment due">{formatDate(card.paymentDueDate)}</Detail>
            <Detail label="APR">
              <span className="tabular">{formatPercent(card.apr)}</span>
            </Detail>
            <Detail label="Rewards">
              <span className="tabular">{formatNumber(card.rewardsPoints)} points</span>
            </Detail>
          </DetailList>
        </div>
      </section>

      <ServicingPayment
        testId="card-pay"
        title="Pay your card"
        description="Pay the minimum, your statement balance, what you owe now, or another amount."
        action={payCardAction}
        target={{ name: "cardId", id: card.id }}
        currency={card.currency}
        accounts={toMoneyAccountOptions(servicingAccounts(accounts, card.currency))}
        choices={cardPaymentChoices(card)}
        allowOther
        confirmLabel="Confirm payment"
        reviewNote="A payment larger than what you owe takes only what you owe."
        available={card.currentBalance > 0}
        unavailable="Nothing to pay. This card has no balance."
      />

      <Card>
        <CardHeader
          title="Card security"
          description="Freeze the card if you have mislaid it. You can lift your own freeze at any time."
        />
        <CardBody>
          <FreezeControl cardId={card.id} status={card.status} />
        </CardBody>
      </Card>

      <Card>
        <CardHeader title="Card transactions" />
        {txPage === null ? (
          <ErrorState
            title="Card transactions are unavailable"
            message="We could not load this card's activity just now. Refresh to try again."
          />
        ) : transactions.length === 0 ? (
          <EmptyState
            title="No card transactions yet"
            description="Purchases, payments and fees on this card will be listed here."
          />
        ) : (
          <ul className="divide-y divide-line">
            {transactions.map((t) => (
              <li key={t.transactionRef} className="flex items-center gap-4 px-5 py-3.5">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-ink">
                    {t.merchantName || t.description || humanise(t.type)}
                  </p>
                  <p className="truncate text-xs text-ink-subtle">
                    {formatDateTime(t.createdAt)}
                    {t.merchantCategory ? ` · ${t.merchantCategory}` : ""} · {humanise(t.type)}
                  </p>
                </div>
                <Money
                  amount={isCardCredit(t.type) ? t.amount : -t.amount}
                  currency={card.currency}
                  size="sm"
                  signed
                  className="shrink-0 font-semibold text-ink"
                />
              </li>
            ))}
          </ul>
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
