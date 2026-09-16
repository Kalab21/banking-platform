import type { Metadata } from "next";
import Link from "next/link";
import { requireSession } from "@/lib/session";
import { getLoans } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
  EmptyState,
  ErrorState,
  PageHeader,
  TableShell,
  Td,
  Th,
  statusTone,
} from "@/components/ui/primitives";
import { formatCurrency, formatDate, formatPercent, humanise } from "@/lib/format";

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
          <ErrorState message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  return (
    <>
      <PageHeader title="Loans" description="Outstanding balance and schedule for each loan." />

      <Card>
        {loans.length === 0 ? (
          <EmptyState
            title="No loans"
            description="Approved loan applications will appear here once disbursed."
          />
        ) : (
          <TableShell label="Your loans">
            <thead>
              <tr>
                <Th>Loan</Th>
                <Th>Status</Th>
                <Th align="right">Principal</Th>
                <Th align="right">Outstanding</Th>
                <Th align="right">Monthly</Th>
                <Th align="right">Rate</Th>
                <Th>Next payment</Th>
              </tr>
            </thead>
            <tbody>
              {loans.map((loan) => (
                <tr key={loan.id} className="hover:bg-sunken">
                  <Td>
                    <Link
                      href={`/loans/${loan.id}`}
                      className="font-medium text-accent hover:underline"
                    >
                      {humanise(loan.loanType)}
                    </Link>
                    <span className="block text-xs text-ink-subtle">#{loan.id}</span>
                  </Td>
                  <Td>
                    <Badge tone={statusTone(loan.status)}>{humanise(loan.status)}</Badge>
                  </Td>
                  <Td align="right">{formatCurrency(loan.principal, loan.currency)}</Td>
                  <Td align="right">{formatCurrency(loan.remainingBalance, loan.currency)}</Td>
                  <Td align="right">{formatCurrency(loan.monthlyPayment, loan.currency)}</Td>
                  <Td align="right">{formatPercent(loan.interestRate)}</Td>
                  <Td>{formatDate(loan.nextPaymentDate)}</Td>
                </tr>
              ))}
            </tbody>
          </TableShell>
        )}
      </Card>
    </>
  );
}
