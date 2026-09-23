"use server";

import { revalidatePath } from "next/cache";
import { acceptOffer, declineOffer, submitApplication } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import { requireSession } from "@/lib/session";
import type { CreateApplicationRequest, CreditProductType } from "@/types/api";

export interface CreditFormState {
  error?: string;
  success?: string;
  fields?: Record<string, string>;
  applicationId?: number;
}

/** Reads a decimal a customer typed, or undefined if they left it blank. */
function money(formData: FormData, field: string): number | undefined {
  const raw = formData.get(field);
  if (typeof raw !== "string" || raw.trim() === "") return undefined;
  const value = Number(raw.replace(/,/g, ""));
  return Number.isFinite(value) ? value : undefined;
}

function whole(formData: FormData, field: string): number | undefined {
  const raw = formData.get(field);
  if (typeof raw !== "string" || raw.trim() === "") return undefined;
  const value = Number.parseInt(raw, 10);
  return Number.isFinite(value) ? value : undefined;
}

/**
 * Applies for a credit product.
 *
 * Only the customer's own answers are read out of the form. There is no branch
 * here that could pass a score, a rate, a term the bank chose, a limit or a
 * tier — not because the form omits them, but because this function does not
 * look for them. A field added to the markup later cannot leak through.
 */
export async function applyForCreditAction(
  _prev: CreditFormState,
  formData: FormData,
): Promise<CreditFormState> {
  const session = await requireSession();

  const productType = formData.get("applicationType");
  if (typeof productType !== "string") {
    return { error: "Choose a product to apply for." };
  }

  const request: CreateApplicationRequest = {
    userId: session.userId,
    applicationType: productType as CreditProductType,
    currency: "USD",
    purpose: (formData.get("purpose") as string) || undefined,
    requestedAmount: money(formData, "requestedAmount"),
    termMonths: whole(formData, "termMonths"),
    annualIncome: money(formData, "annualIncome"),
    monthlyDebtObligations: money(formData, "monthlyDebtObligations"),
    assetValue: money(formData, "assetValue"),
    downPayment: money(formData, "downPayment"),
  };

  try {
    const application = await submitApplication(request);
    revalidatePath("/applications");
    revalidatePath("/dashboard");
    return {
      success: "Application submitted.",
      applicationId: application.id,
    };
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return { error: error.userMessage };
    }
    return { error: "That application could not be submitted." };
  }
}

/**
 * Accepts an offer exactly as it was made.
 *
 * The only thing that travels is which application. The terms are read from the
 * stored offer by the backend, so there is nothing here a customer could edit
 * on the way past.
 */
export async function acceptOfferAction(
  _prev: CreditFormState,
  formData: FormData,
): Promise<CreditFormState> {
  await requireSession();

  const applicationId = whole(formData, "applicationId");
  if (!applicationId) return { error: "That offer could not be found." };

  try {
    await acceptOffer(applicationId);
    revalidatePath("/applications");
    revalidatePath("/dashboard");
    revalidatePath("/cards");
    revalidatePath("/loans");
    return { success: "Offer accepted. We are setting your product up now." };
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return { error: error.userMessage };
    }
    return { error: "That offer could not be accepted." };
  }
}

export async function declineOfferAction(
  _prev: CreditFormState,
  formData: FormData,
): Promise<CreditFormState> {
  await requireSession();

  const applicationId = whole(formData, "applicationId");
  if (!applicationId) return { error: "That offer could not be found." };

  try {
    await declineOffer(applicationId);
    revalidatePath("/applications");
    return { success: "Offer declined." };
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return { error: error.userMessage };
    }
    return { error: "That offer could not be declined." };
  }
}
