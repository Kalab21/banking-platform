"use server";

import { revalidatePath } from "next/cache";
import { disburseLoan, payCreditCard, payOffLoan, repayLoan } from "@/lib/api/banking";
import { requireSession } from "@/lib/session";
import { fieldErrors, servicingPaymentSchema } from "@/lib/validation";
import { classifyMoneyFailure } from "@/features/money/outcome";
import type { MoneyFormState } from "@/features/money/state";

/**
 * Paying a loan or a card, and receiving a loan's funds.
 *
 * The same rules as the move-money actions in `features/money/actions.ts`: the
 * browser names the operation once, when the customer reaches review, and every
 * attempt at that same payment carries the same id as its Idempotency-Key. A
 * missing id is refused, never replaced, because one minted here would be new
 * on every attempt. Which loan or card is paid, and from which account, is
 * checked by the backend against the signed-in customer on every request.
 */

const OPERATION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function operationIdOf(formData: FormData): string | null {
  const value = formData.get("operationId");
  return typeof value === "string" && OPERATION_ID.test(value) ? value : null;
}

function idOf(formData: FormData, name: string): number | null {
  const value = formData.get(name);
  return typeof value === "string" && /^\d+$/.test(value) ? Number(value) : null;
}

/** Display currency only, so a refusal can quote figures; never sent on. */
function currencyOf(formData: FormData): string | undefined {
  const value = formData.get("currency");
  return typeof value === "string" && /^[A-Za-z]{3}$/.test(value) ? value.toUpperCase() : undefined;
}

const MISSING_OPERATION_ID: MoneyFormState = {
  status: "settled",
  outcome: {
    kind: "rejected",
    message: "That request was missing its operation reference. Start the payment again.",
  },
};

const UNKNOWN_TARGET: MoneyFormState = {
  status: "settled",
  outcome: { kind: "rejected", message: "That could not be identified. Reload the page and try again." },
};

function refreshLoan(loanId: number): void {
  revalidatePath(`/loans/${loanId}`);
  revalidatePath("/loans");
  revalidatePath("/accounts");
  revalidatePath("/dashboard");
}

function refreshCard(cardId: number): void {
  revalidatePath(`/cards/${cardId}`);
  revalidatePath("/cards");
  revalidatePath("/accounts");
  revalidatePath("/dashboard");
}

/**
 * The backend caps a repayment at what is owed, so what it reports taking can
 * differ from what was asked; the receipt shows the backend's figure. A payoff
 * takes exactly the figure confirmed, or is refused if that figure has changed.
 */
type Pay = (
  target: number,
  amount: number,
  source: number,
  key: string,
) => Promise<{ reference: string; amount: number }>;

async function payment(
  formData: FormData,
  targetField: "loanId" | "cardId",
  pay: Pay,
  refresh: (target: number) => void,
  message: string,
): Promise<MoneyFormState> {
  await requireSession();

  const operationId = operationIdOf(formData);
  if (!operationId) return MISSING_OPERATION_ID;
  const target = idOf(formData, targetField);
  if (target === null) return UNKNOWN_TARGET;

  const parsed = servicingPaymentSchema.safeParse({
    amount: formData.get("amount"),
    sourceAccountId: formData.get("sourceAccountId"),
  });
  if (!parsed.success) return { status: "invalid", fields: fieldErrors(parsed.error) };

  try {
    const { reference, amount } = await pay(
      target,
      Number(parsed.data.amount),
      Number(parsed.data.sourceAccountId),
      operationId,
    );
    refresh(target);
    return { status: "settled", outcome: { kind: "succeeded", reference, message, amount } };
  } catch (error) {
    return { status: "settled", outcome: classifyMoneyFailure(error, currencyOf(formData)) };
  }
}

export async function repayLoanAction(
  _prev: MoneyFormState,
  formData: FormData,
): Promise<MoneyFormState> {
  return payment(
    formData,
    "loanId",
    async (loanId, amount, source, key) => {
      const done = await repayLoan(loanId, amount, source, key);
      return { reference: done.paymentRef, amount: done.amount };
    },
    refreshLoan,
    "Payment made.",
  );
}

export async function payOffLoanAction(
  _prev: MoneyFormState,
  formData: FormData,
): Promise<MoneyFormState> {
  return payment(
    formData,
    "loanId",
    async (loanId, amount, source, key) => {
      const done = await payOffLoan(loanId, amount, source, key);
      return { reference: done.paymentRef, amount: done.amount };
    },
    refreshLoan,
    "Loan paid off.",
  );
}

export async function payCardAction(
  _prev: MoneyFormState,
  formData: FormData,
): Promise<MoneyFormState> {
  return payment(
    formData,
    "cardId",
    async (cardId, amount, source, key) => {
      const done = await payCreditCard(cardId, amount, source, key);
      return { reference: done.transactionRef, amount: done.amount };
    },
    refreshCard,
    "Payment made.",
  );
}

/**
 * Receiving an approved loan's money.
 *
 * Needs no operation id: a loan is disbursed once, and the backend enforces
 * that itself (see `disburseLoan`). An outcome nobody knows is still reported
 * as unknown, and the loan page then shows whichever state the loan is in.
 */
export async function receiveLoanFundsAction(
  _prev: MoneyFormState,
  formData: FormData,
): Promise<MoneyFormState> {
  await requireSession();

  const loanId = idOf(formData, "loanId");
  if (loanId === null) return UNKNOWN_TARGET;
  const accountId = idOf(formData, "sourceAccountId");
  if (accountId === null) {
    return { status: "invalid", fields: { sourceAccountId: "Choose the account to receive the money" } };
  }

  try {
    const loan = await disburseLoan(loanId, accountId);
    refreshLoan(loanId);
    return {
      status: "settled",
      outcome: {
        kind: "succeeded",
        reference: `Loan #${loan.id}`,
        message: "Funds sent to your account.",
        amount: loan.principal,
      },
    };
  } catch (error) {
    return { status: "settled", outcome: classifyMoneyFailure(error, currencyOf(formData)) };
  }
}
