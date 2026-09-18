"use server";

import { revalidatePath } from "next/cache";
import { createBeneficiary } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import { requireSession } from "@/lib/session";
import { beneficiarySchema, fieldErrors } from "@/lib/validation";
import { maskAccountNumber } from "@/lib/format";

/**
 * Saving a payee.
 *
 * The one thing worth stating plainly: the browser does not get to say whose
 * profile this is saved against. `userId` comes from the session here, so a
 * request with someone else's id in it has nowhere to put that id. The backend
 * checks the same thing again — `AccessGuard.requireTargetUserAllowed` — which
 * is what actually enforces it; this is simply not offering the hole.
 *
 * What comes back is a masked summary rather than the created record. The
 * account number the customer typed has done its job by then, and echoing it
 * into a success message would put it back on a screen it no longer needs to
 * be on.
 */

export type BeneficiaryFormState =
  | { status: "idle" }
  | { status: "invalid"; fields: Record<string, string> }
  | { status: "failed"; error: string }
  | { status: "saved"; name: string; maskedNumber: string };

export const BENEFICIARY_IDLE: BeneficiaryFormState = { status: "idle" };

export async function addBeneficiaryAction(
  _prev: BeneficiaryFormState,
  formData: FormData,
): Promise<BeneficiaryFormState> {
  const session = await requireSession();

  const parsed = beneficiarySchema.safeParse({
    name: formData.get("name"),
    nickname: formData.get("nickname") || undefined,
    accountNumber: formData.get("accountNumber"),
    bankName: formData.get("bankName") || undefined,
    routingNumber: formData.get("routingNumber") || undefined,
    beneficiaryType: formData.get("beneficiaryType"),
    currency: formData.get("currency") || "USD",
  });
  if (!parsed.success) return { status: "invalid", fields: fieldErrors(parsed.error) };

  try {
    const saved = await createBeneficiary(session.userId, {
      name: parsed.data.name,
      nickname: parsed.data.nickname,
      accountNumber: parsed.data.accountNumber,
      bankName: parsed.data.bankName,
      routingNumber: parsed.data.routingNumber || undefined,
      beneficiaryType: parsed.data.beneficiaryType,
      currency: parsed.data.currency.toUpperCase(),
    });

    revalidatePath("/payments");
    return {
      status: "saved",
      name: saved.name,
      maskedNumber: maskAccountNumber(saved.accountNumber),
    };
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return { status: "failed", error: error.userMessage };
    }
    return { status: "failed", error: "That payee could not be saved." };
  }
}
