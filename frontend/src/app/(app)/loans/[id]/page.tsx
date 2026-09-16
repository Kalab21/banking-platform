import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
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
  EmptyState,
  ErrorState,
  PageHeader,
  StatTile,
  TableShell,
  Td,
  Th,
  statusTone,
} from "@/components/ui/primitives";
import { formatCurrency, formatDate, formatPercent, humanise } from "@/lib/format";
import { RepaymentProgress } from "@/features/loans/RepaymentProgress";

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

  return (
    <>
      <PageHeader
        title={`${humanise(loan.loanType)} #${loan.id}`}
        description={`${loan.termMonths}-month term at ${formatPercent(loan.interestRate)} APR`}
        action={
          <Link href="/loans" className="text-sm font-medium text-accent hover:underline">
            Back to loans
          </Link>
        }
      />

      <Badge tone={statusTone(loan.status)}>{humanise(loan.status)}</Badge>

      <section aria-label="Loan summary" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile label="Outstanding" value={formatCurrency(loan.remainingBalance, loan.currency)} />
        <StatTile label="Monthly payment" value={formatCurrency(loan.monthlyPayment, loan.currency)} />
        <StatTile
          label="Payments made"
          value={`${loan.paymentsMade} of ${loan.termMonths}`}
          hint={loan.nextPaymentDate ? `Next due ${formatDate(loan.nextPaymentDate)}` : "No payment due"}
        />
        <StatTile label="Total interest" value={formatCurrency(loan.totalInterest, loan.currency)} />
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader title="Repayment progress" />
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
