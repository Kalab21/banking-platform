"use server";

import { revalidatePath } from "next/cache";
import { deposit, transfer, withdraw } from "@/lib/api/banking";
import { requireSession } from "@/lib/session";
import { depositSchema, fieldErrors, transferSchema, withdrawSchema } from "@/lib/validation";
import { classifyMoneyFailure } from "@/features/money/outcome";
import type { MoneyFormState } from "@/features/money/state";

/**
 * Money movement, one logical operation at a time.
 *
 * The important change from the earlier version is where the idempotency key
 * comes from. It used to be minted inside the action, on every invocation, so
 * every resubmission was a *different* logical operation as far as the backend
 * was concerned. That is safe after a refusal that provably moved nothing, and
 * unsafe after anything else: a request whose outcome nobody knows, retried
 * under a fresh key, is a second debit waiting to happen.
 *
 * Now the browser mints one opaque id when the customer starts an operation and
 * sends it with every attempt at that same operation. Retrying a request that
 * was refused reuses the key, which the backend replays or re-executes
 * according to its own rules. Starting a genuinely new payment mints a new one.
 *
 * The key is opaque and carries nothing: no amount, no account, no timestamp.
 * Two identical transfers a minute apart must both be able to succeed, so it
 * must not be derived from the request.
 */

/** Matches the opaque ids the browser mints — a UUID, and nothing else. */
const OPERATION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function operationIdOf(formData: FormData): string | null {
  const value = formData.get("operationId");
  return typeof value === "string" && OPERATION_ID.test(value) ? value : null;
}

/**
 * A missing or malformed operation id is refused rather than replaced.
 *
 * Generating one here would defeat the point: the whole value of the id is
 * that it is the *same* across attempts, and one minted server-side is new
 * every time.
 */
const MISSING_OPERATION_ID: MoneyFormState = {
  status: "settled",
  outcome: {
    kind: "rejected",
    message: "That request was missing its operation reference. Start the payment again.",
  },
};

function refreshMoneyViews(): void {
  revalidatePath("/dashboard");
  revalidatePath("/accounts");
  revalidatePath("/transactions");
  revalidatePath("/move-money");
}

export async function depositAction(
  _prev: MoneyFormState,
  formData: FormData,
): Promise<MoneyFormState> {
  await requireSession();

  const operationId = operationIdOf(formData);
  if (!operationId) return MISSING_OPERATION_ID;

  const parsed = depositSchema.safeParse({
    accountId: formData.get("accountId"),
    amount: formData.get("amount"),
    description: formData.get("description") || undefined,
  });
  if (!parsed.success) return { status: "invalid", fields: fieldErrors(parsed.error) };

  try {
    const result = await deposit(
      Number(parsed.data.accountId),
      Number(parsed.data.amount),
      operationId,
      parsed.data.description,
    );
    refreshMoneyViews();
    return {
      status: "settled",
      outcome: {
        kind: "succeeded",
        reference: result.transactionRef,
        message: "Deposit complete.",
      },
    };
  } catch (error) {
    return { status: "settled", outcome: classifyMoneyFailure(error) };
  }
}

export async function withdrawAction(
  _prev: MoneyFormState,
  formData: FormData,
): Promise<MoneyFormState> {
  await requireSession();

  const operationId = operationIdOf(formData);
  if (!operationId) return MISSING_OPERATION_ID;

  const parsed = withdrawSchema.safeParse({
    accountId: formData.get("accountId"),
    amount: formData.get("amount"),
    description: formData.get("description") || undefined,
  });
  if (!parsed.success) return { status: "invalid", fields: fieldErrors(parsed.error) };

  try {
    const result = await withdraw(
      Number(parsed.data.accountId),
      Number(parsed.data.amount),
      operationId,
      parsed.data.description,
    );
    refreshMoneyViews();
    return {
      status: "settled",
      outcome: {
        kind: "succeeded",
        reference: result.transactionRef,
        message: "Withdrawal complete.",
      },
    };
  } catch (error) {
    return { status: "settled", outcome: classifyMoneyFailure(error) };
  }
}

export async function transferAction(
  _prev: MoneyFormState,
  formData: FormData,
): Promise<MoneyFormState> {
  await requireSession();

  const operationId = operationIdOf(formData);
  if (!operationId) return MISSING_OPERATION_ID;

  const parsed = transferSchema.safeParse({
    fromAccountId: formData.get("fromAccountId"),
    toAccountId: formData.get("toAccountId"),
    amount: formData.get("amount"),
    description: formData.get("description") || undefined,
  });
  if (!parsed.success) return { status: "invalid", fields: fieldErrors(parsed.error) };

  try {
    const result = await transfer(
      Number(parsed.data.fromAccountId),
      Number(parsed.data.toAccountId),
      Number(parsed.data.amount),
      operationId,
      parsed.data.description,
    );
    refreshMoneyViews();
    return {
      status: "settled",
      outcome: {
        kind: "succeeded",
        // The debit leg's reference is the one the customer will find against
        // the account the money left, which is where they will look.
        reference: result.debit.transactionRef,
        message: "Transfer complete.",
      },
    };
  } catch (error) {
    return { status: "settled", outcome: classifyMoneyFailure(error) };
  }
}
