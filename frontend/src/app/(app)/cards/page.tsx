import type { Metadata } from "next";
import Link from "next/link";
import { requireSession } from "@/lib/session";
import { getCreditCards } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
  CardBody,
  EmptyState,
  ErrorState,
  PageHeader,
  statusTone,
} from "@/components/ui/primitives";
import { formatCurrency, formatDate, formatPercent, humanise, maskCardNumber } from "@/lib/format";

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
          <ErrorState message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  return (
    <>
      <PageHeader
        title="Credit cards"
        description="Limits, balances and rewards. Card numbers are always shown masked."
      />

      {cards.length === 0 ? (
        <Card>
          <EmptyState
            title="No credit cards"
            description="Approved card applications will appear here."
          />
        </Card>
      ) : (
        <div className="grid gap-6 md:grid-cols-2">
          {cards.map((card) => {
            const used = card.creditLimit > 0 ? (card.currentBalance / card.creditLimit) * 100 : 0;
            return (
              <Card key={card.id}>
                <CardBody className="space-y-4">
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <Link
                        href={`/cards/${card.id}`}
                        className="text-sm font-semibold text-accent hover:underline"
                      >
                        {humanise(card.cardType)} card
                      </Link>
                      <p className="tabular mt-0.5 text-sm text-ink-subtle">
                        {maskCardNumber(card.cardNumber)}
                      </p>
                    </div>
                    <Badge tone={statusTone(card.status)}>{humanise(card.status)}</Badge>
                  </div>

                  <div>
                    <div className="flex items-baseline justify-between">
                      <span className="text-xs text-ink-subtle">Balance</span>
                      <span className="tabular text-lg font-semibold text-ink">
                        {formatCurrency(card.currentBalance, card.currency)}
                      </span>
                    </div>
                    <div
                      className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-sunken"
                      role="img"
                      aria-label={`${Math.round(used)}% of the credit limit used.`}
                    >
                      <div
                        className="h-full rounded-full bg-accent"
                        style={{ width: `${Math.min(Math.max(used, 0), 100)}%` }}
                      />
                    </div>
                    <p className="mt-1.5 text-xs text-ink-subtle">
                      {formatCurrency(card.availableCredit, card.currency)} available of{" "}
                      {formatCurrency(card.creditLimit, card.currency)}
                    </p>
                  </div>

                  <dl className="grid grid-cols-2 gap-3 border-t border-line pt-3 text-sm">
                    <div>
                      <dt className="text-xs text-ink-subtle">Minimum due</dt>
                      <dd className="tabular text-ink">
                        {formatCurrency(card.minimumPaymentDue, card.currency)}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-xs text-ink-subtle">Due date</dt>
                      <dd className="text-ink">{formatDate(card.paymentDueDate)}</dd>
                    </div>
                    <div>
                      <dt className="text-xs text-ink-subtle">APR</dt>
                      <dd className="tabular text-ink">{formatPercent(card.apr)}</dd>
                    </div>
                    <div>
                      <dt className="text-xs text-ink-subtle">Rewards</dt>
                      <dd className="tabular text-ink">{card.rewardsPoints} pts</dd>
                    </div>
                  </dl>
                </CardBody>
              </Card>
            );
          })}
        </div>
      )}
    </>
  );
}
