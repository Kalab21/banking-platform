"use server";

import { revalidatePath } from "next/cache";
import { apiFetch } from "@/lib/api/client";
import { ApiError, NetworkError } from "@/lib/api/client";
import { requireSession } from "@/lib/session";
import type { CreditCard } from "@/types/api";

export interface CardStatusFormState {
  error?: string;
  success?: string;
}

/**
 * Freezes or unfreezes a card.
 *
 * Only the two states a cardholder has authority over are reachable from here.
 * A form that could send DEFAULTED or SYSTEM_BLOCKED would be refused by the
 * backend anyway — it keeps two transition tables and a customer's is two moves
 * long — but offering the control at all would be telling the customer
 * something untrue about what they can do.
 */
export async function setCardFreezeAction(
  _prev: CardStatusFormState,
  formData: FormData,
): Promise<CardStatusFormState> {
  await requireSession();

  const cardId = Number.parseInt(String(formData.get("cardId")), 10);
  const intent = String(formData.get("intent"));
  if (!Number.isFinite(cardId)) return { error: "That card could not be found." };

  const status = intent === "freeze" ? "CUSTOMER_FROZEN" : "ACTIVE";

  try {
    await apiFetch<CreditCard>(`/api/credit-cards/${cardId}/status`, {
      method: "PUT",
      body: JSON.stringify({ status }),
    });
    revalidatePath("/cards");
    revalidatePath(`/cards/${cardId}`);
    return {
      success: status === "CUSTOMER_FROZEN" ? "Card frozen." : "Card unfrozen.",
    };
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return { error: error.userMessage };
    }
    return { error: "That card could not be updated." };
  }
}
