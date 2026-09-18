import type { Metadata } from "next";
import { requireSession } from "@/lib/session";
import { getAccounts, getBeneficiaries, getPayments, getScheduledPayments } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  PageHeader,
  TableShell,
  Td,
  Th,
  statusTone,
} from "@/components/ui/primitives";
import { formatCurrency, formatDate, humanise, maskAccountNumber } from "@/lib/format";
import type { Payment } from "@/types/api";
import { AddBeneficiary } from "@/features/payments/AddBeneficiary";

export const metadata: Metadata = { title: "Payments" };

export default async function PaymentsPage() {
  const session = await requireSession();

  let accounts;
  let beneficiaries;
  try {
    [accounts, beneficiaries] = await Promise.all([
      getAccounts(session.userId),
      getBeneficiaries(session.userId),
    ]);
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Payments" />
          <ErrorState message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  const [paymentPages, scheduledPages] = await Promise.all([
    Promise.all(accounts.map((a) => getPayments(a.id))),
    Promise.all(accounts.map((a) => getScheduledPayments(a.id))),
  ]);

  const payments: Payment[] = paymentPages
    .flat()
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  const scheduled: Payment[] = scheduledPages.flat();

  return (
    <>
      <PageHeader
        title="Payments"
        description="Beneficiaries, settled payments and anything scheduled to run."
      />

      {/*
        * The only write on this page. Creating a payment moves money and has no
        * idempotency record behind it, so that stays out of the console; saving
        * a payee moves nothing. The component is handed no beneficiary data —
        * it is a form, and the session supplies whose profile it writes to.
        */}
      <AddBeneficiary />

      <Card>
        <CardHeader title="Beneficiaries" description="Payees saved against your profile." />
        {beneficiaries.length === 0 ? (
          <EmptyState
            title="No beneficiaries saved"
            description="Saved payees appear here once added to your profile."
          />
        ) : (
          <TableShell label="Beneficiaries">
            <thead>
              <tr>
                <Th>Name</Th>
                <Th>Account</Th>
                <Th>Bank</Th>
                <Th>Type</Th>
                <Th>Currency</Th>
                <Th>Verified</Th>
              </tr>
            </thead>
            <tbody>
              {beneficiaries.map((b) => (
                <tr key={b.id} className="hover:bg-sunken">
                  <Td>
                    <span className="font-medium text-ink">{b.name}</span>
                    {b.nickname ? (
                      <span className="block text-xs text-ink-subtle">{b.nickname}</span>
                    ) : null}
                  </Td>
                  <Td>{maskAccountNumber(b.accountNumber)}</Td>
                  <Td>{b.bankName || "—"}</Td>
                  <Td>{humanise(b.beneficiaryType)}</Td>
                  <Td>{b.currency}</Td>
                  <Td>
                    <Badge tone={b.verified ? "positive" : "caution"}>
                      {b.verified ? "Verified" : "Unverified"}
                    </Badge>
                  </Td>
                </tr>
              ))}
            </tbody>
          </TableShell>
        )}
      </Card>

      <Card>
        <CardHeader
          title="Scheduled payments"
          description="Recurring and future-dated instructions."
        />
        {scheduled.length === 0 ? (
          <EmptyState title="Nothing scheduled" />
        ) : (
          <TableShell label="Scheduled payments">
            <thead>
              <tr>
                <Th>Reference</Th>
                <Th>Description</Th>
                <Th>Pattern</Th>
                <Th>Next run</Th>
                <Th>Status</Th>
                <Th align="right">Amount</Th>
              </tr>
            </thead>
            <tbody>
              {scheduled.map((p) => (
                <tr key={p.paymentRef} className="hover:bg-sunken">
                  <Td className="font-mono text-xs text-ink-subtle">{p.paymentRef}</Td>
                  <Td>{p.description || "—"}</Td>
                  <Td>{p.recurring ? humanise(p.recurrencePattern) : "One-off"}</Td>
                  <Td>{formatDate(p.nextExecutionDate)}</Td>
                  <Td>
                    <Badge tone={statusTone(p.status)}>{humanise(p.status)}</Badge>
                  </Td>
                  <Td align="right">{formatCurrency(p.amount, p.currency)}</Td>
                </tr>
              ))}
            </tbody>
          </TableShell>
        )}
      </Card>

      <Card>
        <CardHeader title="Payment history" />
        {payments.length === 0 ? (
          <EmptyState
            title="No payments yet"
            description="Payments made from your accounts will be listed here."
          />
        ) : (
          <TableShell label="Payment history">
            <thead>
              <tr>
                <Th>Date</Th>
                <Th>Reference</Th>
                <Th>Description</Th>
                <Th>Type</Th>
                <Th>Status</Th>
                <Th align="right">Amount</Th>
              </tr>
            </thead>
            <tbody>
              {payments.slice(0, 40).map((p) => (
                <tr key={p.paymentRef} className="hover:bg-sunken">
                  <Td>{formatDate(p.createdAt)}</Td>
                  <Td className="font-mono text-xs text-ink-subtle">{p.paymentRef}</Td>
                  <Td>{p.description || "—"}</Td>
                  <Td>{humanise(p.paymentType)}</Td>
                  <Td>
                    <Badge tone={statusTone(p.status)}>{humanise(p.status)}</Badge>
                  </Td>
                  <Td align="right">{formatCurrency(p.amount, p.currency)}</Td>
                </tr>
              ))}
            </tbody>
          </TableShell>
        )}
      </Card>
    </>
  );
}
