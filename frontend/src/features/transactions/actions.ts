"use server";

import { revalidatePath } from "next/cache";
import { deposit, withdraw, transfer } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import { requireSession } from "@/lib/session";
import { depositSchema, fieldErrors, transferSchema, withdrawSchema } from "@/lib/validation";

/**
 * Money-movement server actions.
 *
 * Validation here mirrors the backend's Bean Validation constraints; the
 * backend re-validates and remains the authority.
 *
 * Each action mints one `Idempotency-Key` and uses it for the single call it
 * makes. That is what it can honestly claim to cover: if the request is retried
 * on its way to the gateway, or the response is lost after the money has moved,
 * the backend executes it once. It does not cover a double-click, because the
 * browser sends two separate actions and each mints its own key — the disabled
 * submit button is what handles that.
 */

export interface MoneyFormState {
  error?: string;
  success?: string;
  fields?: Record<string, string>;
}

function toState(error: unknown, fallback: string): MoneyFormState {
  if (error instanceof ApiError) return { error: error.userMessage };
  if (error instanceof NetworkError) return { error: error.userMessage };
  return { error: fallback };
}

function refreshMoneyViews(): void {
  revalidatePath("/dashboard");
  revalidatePath("/accounts");
  revalidatePath("/transactions");
}

export async function depositAction(
  _prev: MoneyFormState,
  formData: FormData,
): Promise<MoneyFormState> {
  await requireSession();

  const parsed = depositSchema.safeParse({
    accountId: formData.get("accountId"),
    amount: formData.get("amount"),
    description: formData.get("description") || undefined,
  });
  if (!parsed.success) return { fields: fieldErrors(parsed.error) };

  try {
    const result = await deposit(
      Number(parsed.data.accountId),
      Number(parsed.data.amount),
      crypto.randomUUID(),
      parsed.data.description,
    );
    refreshMoneyViews();
    return { success: `Deposit complete. Reference ${result.transactionRef}.` };
  } catch (error) {
    return toState(error, "The deposit could not be completed.");
  }
}

export async function withdrawAction(
  _prev: MoneyFormState,
  formData: FormData,
): Promise<MoneyFormState> {
  await requireSession();

  const parsed = withdrawSchema.safeParse({
    accountId: formData.get("accountId"),
    amount: formData.get("amount"),
    description: formData.get("description") || undefined,
  });
  if (!parsed.success) return { fields: fieldErrors(parsed.error) };

  try {
    const result = await withdraw(
      Number(parsed.data.accountId),
      Number(parsed.data.amount),
      crypto.randomUUID(),
      parsed.data.description,
    );
    refreshMoneyViews();
    return { success: `Withdrawal complete. Reference ${result.transactionRef}.` };
  } catch (error) {
    return toState(error, "The withdrawal could not be completed.");
  }
}

export async function transferAction(
  _prev: MoneyFormState,
  formData: FormData,
): Promise<MoneyFormState> {
  await requireSession();

  const parsed = transferSchema.safeParse({
    fromAccountId: formData.get("fromAccountId"),
    toAccountId: formData.get("toAccountId"),
    amount: formData.get("amount"),
    description: formData.get("description") || undefined,
  });
  if (!parsed.success) return { fields: fieldErrors(parsed.error) };

  try {
    const result = await transfer(
      Number(parsed.data.fromAccountId),
      Number(parsed.data.toAccountId),
      Number(parsed.data.amount),
      crypto.randomUUID(),
      parsed.data.description,
    );
    refreshMoneyViews();
    return { success: `Transfer complete. Reference ${result.debit.transactionRef}.` };
  } catch (error) {
    return toState(error, "The transfer could not be completed.");
  }
}
