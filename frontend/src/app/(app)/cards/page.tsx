import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { requireSession } from "@/lib/session";
import { getCreditCards } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
  Detail,
  DetailList,
  EmptyState,
  ErrorState,
  Money,
  PageHeader,
  ProgressBar,
  statusTone,
} from "@/components/ui/primitives";
import { formatCurrency, formatDate, formatPercent, formatNumber, humanise } from "@/lib/format";
import { VirtualCard } from "@/features/cards/VirtualCard";
import { utilisation } from "@/features/cards/utilisation";

export const metadata: Metadata = { title: "Credit cards" };

export default async function CardsPage() {
  const session = await requireSession();

  let cards;
  try {
    cards = await getCreditCards(session.userId);
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Credit cards" />
          <ErrorState title="We could not load your cards" message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  return (
    <>
      <PageHeader
        title="Credit cards"
        description="Balances, limits and rewards. Card numbers are always shown masked."
      />

      {cards.length === 0 ? (
        <Card>
          <EmptyState
            title="No credit cards"
            description="When you have a card, its limit, balance and statement dates appear here."
            action={
              <Link
                href="/credit"
                className="inline-flex items-center gap-1 text-sm font-medium text-[var(--accent)] hover:underline"
              >
                Explore credit
                <ArrowRight aria-hidden className="size-4" />
              </Link>
            }
          />
        </Card>
      ) : (
        <div className="space-y-6">
          {cards.map((card) => {
            const used = utilisation(card.currentBalance, card.creditLimit);
            return (
              <Card key={card.id} className="overflow-hidden">
                <div className="grid gap-6 p-5 sm:p-6 lg:grid-cols-[minmax(0,20rem)_1fr] lg:items-start">
                  <VirtualCard card={card} />

                  <div className="min-w-0 space-y-5">
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div>
                        <h2 className="text-base font-semibold text-ink">
                          {humanise(card.cardType)} card
                        </h2>
                        <p className="tabular mt-0.5 text-sm text-ink-subtle">
                          {card.maskedCardNumber}
                        </p>
                      </div>
                      <Badge tone={statusTone(card.status)}>{humanise(card.status)}</Badge>
                    </div>

                    <div>
                      <div className="flex flex-wrap items-end justify-between gap-2">
                        <div>
                          <p className="text-xs uppercase tracking-wide text-ink-subtle">
                            Current balance
                          </p>
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
                          {used.percent}% of {formatCurrency(card.creditLimit, card.currency)} limit
                          used
                        </p>
                      </div>
                    </div>

                    <DetailList columns={2} className="border-t border-line pt-4">
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

                    <Link
                      href={`/cards/${card.id}`}
                      className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
                    >
                      View card activity
                      <ArrowRight aria-hidden="true" className="h-4 w-4" />
                    </Link>
                  </div>
                </div>
              </Card>
            );
          })}
        </div>
      )}
    </>
  );
}
