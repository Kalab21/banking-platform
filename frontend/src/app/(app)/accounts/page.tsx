import type { Metadata } from "next";
import { requireSession } from "@/lib/session";
import { getAccounts } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import { Card, EmptyState, ErrorState, Money, PageHeader } from "@/components/ui/primitives";
import { AccountCard } from "@/features/accounts/AccountCard";

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
          <ErrorState title="We could not load your accounts" message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  const total = accounts.reduce((sum, a) => sum + a.balance, 0);
  const currency = accounts[0]?.currency ?? "USD";

  return (
    <>
      <PageHeader
        title="Accounts"
        description="Every account you hold with Northbank."
        action={
          accounts.length > 0 ? (
            <div className="text-right">
              <p className="text-xs uppercase tracking-wide text-ink-subtle">Total balance</p>
              <Money amount={total} currency={currency} size="lg" className="mt-0.5 block text-ink" />
            </div>
          ) : undefined
        }
      />

      {accounts.length === 0 ? (
        <Card>
          <EmptyState
            title="No accounts yet"
            description="Once an account application is approved it will appear here, with its balance, overdraft position and full transaction history."
          />
        </Card>
      ) : (
        /*
         * Cards rather than a table. A table is the right shape for comparing
         * forty rows on a desk; a customer with three accounts is not
         * comparing, they are looking for one balance — and six columns do not
         * survive a phone.
         */
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {accounts.map((account) => (
            <AccountCard key={account.id} account={account} />
          ))}
        </div>
      )}
    </>
  );
}
