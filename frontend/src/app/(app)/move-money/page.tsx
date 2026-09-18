import type { Metadata } from "next";
import { requireSession } from "@/lib/session";
import { getAccounts } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import { ErrorState, PageHeader } from "@/components/ui/primitives";
import { MoveMoney } from "@/features/money/MoveMoney";
import { toMoneyAccountOptions } from "@/features/transactions/money-account";

export const metadata: Metadata = { title: "Move money" };

/**
 * Money movement has its own route.
 *
 * Transactions used to be both the forms and the history, which made the page
 * answer two different questions at once. Here "move money" is the action and
 * `/transactions` is the record, and each reads as one thing.
 */
export default async function MoveMoneyPage() {
  const session = await requireSession();

  let accounts;
  try {
    accounts = await getAccounts(session.userId);
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Move money" />
          <ErrorState title="We could not load your accounts" message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  return (
    <>
      <PageHeader
        title="Move money"
        description="Transfer between your accounts, or record a deposit or withdrawal."
      />
      {/*
       * The forms are a Client Component, so they get a narrowed view of each
       * account rather than the account itself — everything handed across that
       * boundary is serialised into the page.
       */}
      <MoveMoney accounts={toMoneyAccountOptions(accounts)} />
    </>
  );
}
