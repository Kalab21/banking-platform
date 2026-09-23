import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { requireSession } from "@/lib/session";
import { getLoans } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
  CardBody,
  Detail,
  DetailList,
  EmptyState,
  ErrorState,
  Money,
  PageHeader,
  ProgressBar,
  statusTone,
} from "@/components/ui/primitives";
import { formatCurrency, formatDate, formatPercent, humanise } from "@/lib/format";
import { balanceProgress } from "@/features/loans/progress";

export const metadata: Metadata = { title: "Loans" };

export default async function LoansPage() {
  const session = await requireSession();

  let loans;
  try {
    loans = await getLoans(session.userId);
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Loans" />
          <ErrorState title="We could not load your loans" message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  const outstanding = loans
    .filter((l) => l.status === "ACTIVE")
    .reduce((sum, l) => sum + l.remainingBalance, 0);
  const currency = loans[0]?.currency ?? "USD";

  return (
    <>
      <PageHeader
        title="Loans"
        description="What you owe, what you pay each month, and when the next payment is due."
        action={
          loans.length > 0 ? (
            <div className="text-right">
              <p className="text-xs uppercase tracking-wide text-ink-subtle">Total outstanding</p>
              <Money
                amount={outstanding}
                currency={currency}
                size="lg"
                className="mt-0.5 block text-ink"
              />
            </div>
          ) : undefined
        }
      />

      {loans.length === 0 ? (
        <Card>
          <EmptyState
            title="No loans"
            description="When you have a loan, its schedule and payoff figures appear here."
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
        <div className="grid gap-4 lg:grid-cols-2">
          {loans.map((loan) => {
            const progress = balanceProgress(loan.principal, loan.remainingBalance);
            return (
              <Card key={loan.id}>
                <CardBody className="space-y-5 p-5 sm:p-6">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0">
                      <h2 className="text-base font-semibold text-ink">
                        {humanise(loan.loanType)}
                      </h2>
                      <p className="text-xs text-ink-subtle">
                        {loan.termMonths}-month term · loan #{loan.id}
                      </p>
                    </div>
                    <Badge tone={statusTone(loan.status)}>{humanise(loan.status)}</Badge>
                  </div>

                  <div>
                    <p className="text-xs uppercase tracking-wide text-ink-subtle">Remaining</p>
                    <Money
                      amount={loan.remainingBalance}
                      currency={loan.currency}
                      size="lg"
                      className="mt-1 block text-ink"
                    />
                  </div>

                  {progress ? (
                    <div>
                      <ProgressBar
                        value={progress.percent}
                        label={`Loan balance reduced by ${progress.percent}%, from ${formatCurrency(loan.principal, loan.currency)} to ${formatCurrency(loan.remainingBalance, loan.currency)}`}
                      />
                      {/*
                       * "Balance progress", not "principal repaid": a payment
                       * covers interest as well, and the record does not say
                       * how much of it reached the principal.
                       */}
                      <p className="mt-2 text-xs text-ink-subtle">
                        {progress.percent}% of the original{" "}
                        {formatCurrency(loan.principal, loan.currency)} balance repaid
                      </p>
                    </div>
                  ) : null}

                  <DetailList columns={2} className="border-t border-line pt-4">
                    <Detail label="Monthly payment">
                      <span className="tabular">
                        {formatCurrency(loan.monthlyPayment, loan.currency)}
                      </span>
                    </Detail>
                    <Detail label="Rate">
                      <span className="tabular">{formatPercent(loan.interestRate)}</span>
                    </Detail>
                    <Detail label="Next payment">{formatDate(loan.nextPaymentDate)}</Detail>
                    <Detail label="Payments made">
                      <span className="tabular">
                        {loan.paymentsMade} of {loan.termMonths}
                      </span>
                    </Detail>
                  </DetailList>

                  <Link
                    href={`/loans/${loan.id}`}
                    className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
                  >
                    View loan
                    <ArrowRight aria-hidden="true" className="h-4 w-4" />
                  </Link>
                </CardBody>
              </Card>
            );
          })}
        </div>
      )}
    </>
  );
}
