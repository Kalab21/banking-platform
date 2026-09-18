import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { requireSession } from "@/lib/session";
import {
  getAmortizationSchedule,
  getLoan,
  getLoanRepayments,
  getPayoffQuote,
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
  TableShell,
  Td,
  Th,
  statusTone,
} from "@/components/ui/primitives";
import { formatCurrency, formatDate, formatPercent, humanise } from "@/lib/format";
import { RepaymentProgress } from "@/features/loans/RepaymentProgress";
import { balanceProgress } from "@/features/loans/progress";

export const metadata: Metadata = { title: "Loan" };

export default async function LoanDetailPage({ params }: { params: Promise<{ id: string }> }) {
  await requireSession();
  const { id } = await params;
  const loanId = Number(id);
  if (!Number.isFinite(loanId)) notFound();

  let loan;
  let schedule;
  let quote;
  let repayments;
  try {
    loan = await getLoan(loanId);
    [schedule, quote, repayments] = await Promise.all([
      getAmortizationSchedule(loanId),
      getPayoffQuote(loanId),
      getLoanRepayments(loanId),
    ]);
  } catch (error) {
    if (error instanceof ApiError && error.isNotFound) notFound();
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Loan" />
          <ErrorState message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  const progress = balanceProgress(loan.principal, loan.remainingBalance);

  return (
    <>
      <Link
        href="/loans"
        className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
      >
        <ArrowLeft aria-hidden="true" className="h-4 w-4" />
        Back to loans
      </Link>

      <section
        aria-labelledby="loan-heading"
        className="rounded-[var(--radius-card)] border border-line bg-surface p-6 sm:p-7"
      >
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <h1 id="loan-heading" className="text-xl font-semibold tracking-tight text-ink">
              {humanise(loan.loanType)}
            </h1>
            <p className="mt-1 text-sm text-ink-subtle">
              {loan.termMonths}-month term at {formatPercent(loan.interestRate)} · loan #{loan.id}
            </p>
          </div>
          <Badge tone={statusTone(loan.status)}>{humanise(loan.status)}</Badge>
        </div>

        <div className="mt-6 flex flex-wrap items-end gap-x-10 gap-y-4">
          <div>
            <p className="text-xs uppercase tracking-wide text-ink-subtle">Remaining balance</p>
            <Money
              amount={loan.remainingBalance}
              currency={loan.currency}
              size="lg"
              className="mt-1 block text-ink"
            />
          </div>
          <div>
            <p className="text-xs uppercase tracking-wide text-ink-subtle">Monthly payment</p>
            <Money
              amount={loan.monthlyPayment}
              currency={loan.currency}
              size="lg"
              className="mt-1 block text-ink"
            />
          </div>
        </div>

        {progress ? (
          <div className="mt-6">
            <ProgressBar
              value={progress.percent}
              label={`Loan balance reduced by ${progress.percent}%, from ${formatCurrency(loan.principal, loan.currency)} to ${formatCurrency(loan.remainingBalance, loan.currency)}`}
            />
            <p className="mt-2 text-xs text-ink-subtle">
              {progress.percent}% of the original {formatCurrency(loan.principal, loan.currency)}{" "}
              balance repaid
            </p>
          </div>
        ) : null}

        <DetailList columns={3} className="mt-6 border-t border-line pt-5">
          <Detail label="Original principal">
            <span className="tabular">{formatCurrency(loan.principal, loan.currency)}</span>
          </Detail>
          <Detail label="Payments made">
            <span className="tabular">
              {loan.paymentsMade} of {loan.termMonths}
            </span>
          </Detail>
          <Detail label="Next payment">{formatDate(loan.nextPaymentDate)}</Detail>
          <Detail label="Interest rate">
            <span className="tabular">{formatPercent(loan.interestRate)}</span>
          </Detail>
          <Detail label="Total interest">
            <span className="tabular">{formatCurrency(loan.totalInterest, loan.currency)}</span>
          </Detail>
          <Detail label="Disbursed">{formatDate(loan.disbursedAt)}</Detail>
        </DetailList>
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader title="Balance progress" />
          <CardBody>
            <RepaymentProgress
              principal={loan.principal}
              remainingBalance={loan.remainingBalance}
              currency={loan.currency}
            />
          </CardBody>
        </Card>

        <Card>
          <CardHeader title="Settlement quote" description="What it would take to close this loan today." />
          <CardBody>
            {quote ? (
              <dl className="space-y-2 text-sm">
                <div className="flex justify-between">
                  <dt className="text-ink-muted">Remaining balance</dt>
                  <dd className="tabular text-ink">
                    {formatCurrency(quote.remainingBalance, loan.currency)}
                  </dd>
                </div>
                <div className="flex justify-between">
                  <dt className="text-ink-muted">Accrued interest</dt>
                  <dd className="tabular text-ink">
                    {formatCurrency(quote.accruedInterest, loan.currency)}
                  </dd>
                </div>
                <div className="flex justify-between border-t border-line pt-2">
                  <dt className="font-medium text-ink">Total payoff</dt>
                  <dd className="tabular font-semibold text-ink">
                    {formatCurrency(quote.totalPayoffAmount, loan.currency)}
                  </dd>
                </div>
                <div className="flex justify-between">
                  <dt className="text-ink-muted">Payments remaining</dt>
                  <dd className="tabular text-ink">{quote.paymentsRemaining}</dd>
                </div>
                <p className="pt-2 text-xs text-ink-subtle">
                  Quoted {formatDate(quote.quoteDate)}.
                </p>
              </dl>
            ) : (
              <EmptyState title="No quote available" description="This loan is not currently active." />
            )}
          </CardBody>
        </Card>
      </div>

      <Card>
        <CardHeader
          title="Amortization schedule"
          description={`${schedule.length} scheduled instalments`}
        />
        {schedule.length === 0 ? (
          <EmptyState title="No schedule generated" />
        ) : (
          <TableShell label="Amortization schedule">
            <thead>
              <tr>
                <Th>#</Th>
                <Th>Due</Th>
                <Th>Status</Th>
                <Th align="right">Payment</Th>
                <Th align="right">Principal</Th>
                <Th align="right">Interest</Th>
                <Th align="right">Balance after</Th>
              </tr>
            </thead>
            <tbody>
              {schedule.map((row) => (
                <tr key={row.paymentNumber} className="hover:bg-sunken">
                  <Td>{row.paymentNumber}</Td>
                  <Td>{formatDate(row.dueDate)}</Td>
                  <Td>
                    <Badge tone={statusTone(row.status)}>{humanise(row.status)}</Badge>
                  </Td>
                  <Td align="right">{formatCurrency(row.scheduledPayment, loan.currency)}</Td>
                  <Td align="right">{formatCurrency(row.principalPortion, loan.currency)}</Td>
                  <Td align="right">{formatCurrency(row.interestPortion, loan.currency)}</Td>
                  <Td align="right">{formatCurrency(row.remainingBalance, loan.currency)}</Td>
                </tr>
              ))}
            </tbody>
          </TableShell>
        )}
      </Card>

      {repayments.length > 0 ? (
        <Card>
          <CardHeader title="Repayment history" />
          <TableShell label="Repayments made">
            <thead>
              <tr>
                <Th>Date</Th>
                <Th>Reference</Th>
                <Th align="right">Amount</Th>
                <Th align="right">Principal</Th>
                <Th align="right">Interest</Th>
                <Th>Type</Th>
              </tr>
            </thead>
            <tbody>
              {repayments.map((r) => (
                <tr key={r.paymentRef} className="hover:bg-sunken">
                  <Td>{formatDate(r.createdAt)}</Td>
                  <Td className="font-mono text-xs text-ink-subtle">{r.paymentRef.slice(0, 8)}…</Td>
                  <Td align="right">{formatCurrency(r.amount, loan.currency)}</Td>
                  <Td align="right">{formatCurrency(r.principalPaid, loan.currency)}</Td>
                  <Td align="right">{formatCurrency(r.interestPaid, loan.currency)}</Td>
                  <Td>{r.isEarlyPayoff ? "Early payoff" : "Scheduled"}</Td>
                </tr>
              ))}
            </tbody>
          </TableShell>
        </Card>
      ) : null}
    </>
  );
}
