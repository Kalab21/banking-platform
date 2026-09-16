import type { Metadata } from "next";
import Link from "next/link";
import { requireSession } from "@/lib/session";
import { getAccounts } from "@/lib/api/banking";
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
import { formatCurrency, humanise, maskAccountNumber } from "@/lib/format";

export const metadata: Metadata = { title: "Accounts" };

export default async function AccountsPage() {
  const session = await requireSession();

  let accounts;
  try {
    accounts = await getAccounts(session.userId);
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Accounts" />
          <ErrorState message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  return (
    <>
      <PageHeader
        title="Accounts"
        description="Balances, overdraft position and status for every account you hold."
      />

      <Card>
        {accounts.length === 0 ? (
          <EmptyState
            title="No accounts yet"
            description="Once an account application is approved it will appear here."
          />
        ) : (
          <TableShell label="Your accounts">
            <thead>
              <tr>
                <Th>Account</Th>
                <Th>Type</Th>
                <Th>Status</Th>
                <Th align="right">Balance</Th>
                <Th align="right">Available</Th>
                <Th align="right">Overdraft used</Th>
              </tr>
            </thead>
            <tbody>
              {accounts.map((a) => (
                <tr key={a.id} className="hover:bg-sunken">
                  <Td>
                    <Link
                      href={`/accounts/${a.id}`}
                      className="font-medium text-accent hover:underline"
                    >
                      {maskAccountNumber(a.accountNumber)}
                    </Link>
                  </Td>
                  <Td>{humanise(a.accountType)}</Td>
                  <Td>
                    <Badge tone={statusTone(a.status)}>{humanise(a.status)}</Badge>
                  </Td>
                  <Td align="right" className={a.balance < 0 ? "text-critical" : undefined}>
                    {formatCurrency(a.balance, a.currency)}
                  </Td>
                  <Td align="right">{formatCurrency(a.availableBalance, a.currency)}</Td>
                  <Td align="right">
                    {a.overdraftBalance > 0
                      ? formatCurrency(a.overdraftBalance, a.currency)
                      : "—"}
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
