"use server";

import { revalidatePath } from "next/cache";
import { reviewKycDocument, setKycStatus, submitKycDocument } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import { requireSession, requireStaffSession } from "@/lib/session";
import { fieldErrors, kycDocumentSchema } from "@/lib/validation";

export interface KycFormState {
  error?: string;
  success?: string;
  fields?: Record<string, string>;
}

export async function submitKycAction(
  _prev: KycFormState,
  formData: FormData,
): Promise<KycFormState> {
  const session = await requireSession();

  const parsed = kycDocumentSchema.safeParse({
    documentType: formData.get("documentType"),
    documentRef: formData.get("documentRef"),
  });
  if (!parsed.success) return { fields: fieldErrors(parsed.error) };

  try {
    await submitKycDocument(session.userId, parsed.data.documentType, parsed.data.documentRef);
    revalidatePath("/profile");
    revalidatePath("/dashboard");
    return { success: "Document submitted. It will be reviewed by our team." };
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return { error: error.userMessage };
    }
    return { error: "That document could not be submitted." };
  }
}

/**
 * Staff decision on a submitted document.
 *
 * `requireStaffSession` only keeps non-staff off the page. The backend endpoint
 * carries `@PreAuthorize("hasRole('EMPLOYEE') or hasRole('ADMIN')")`, which is
 * what actually enforces this.
 */
export async function reviewKycAction(
  _prev: KycFormState,
  formData: FormData,
): Promise<KycFormState> {
  await requireStaffSession();

  const documentId = Number(formData.get("documentId"));
  const decision = String(formData.get("decision"));
  const rejectionReason = String(formData.get("rejectionReason") ?? "").trim();

  if (!Number.isFinite(documentId)) return { error: "That document could not be identified." };
  if (decision !== "APPROVED" && decision !== "REJECTED") {
    return { error: "Choose approve or reject." };
  }
  if (decision === "REJECTED" && rejectionReason.length === 0) {
    return { error: "A rejection needs a reason the customer can act on." };
  }

  try {
    await reviewKycDocument(documentId, decision, decision === "REJECTED" ? rejectionReason : undefined);
    revalidatePath("/admin/kyc");
    return { success: `Document ${decision === "APPROVED" ? "approved" : "rejected"}.` };
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return { error: error.userMessage };
    }
    return { error: "That review could not be recorded." };
  }
}

/**
 * Staff decision on the customer's identity as a whole.
 *
 * The backend refuses a customer (`@PreAuthorize`) and refuses a member of
 * staff deciding their own; the page hiding the control for either is a
 * convenience, not the rule.
 */
export async function setKycStatusAction(
  _prev: KycFormState,
  formData: FormData,
): Promise<KycFormState> {
  await requireStaffSession();

  const userId = Number(formData.get("userId"));
  const decision = String(formData.get("decision"));
  if (!Number.isFinite(userId)) return { error: "That customer could not be identified." };
  if (decision !== "APPROVED" && decision !== "REJECTED") {
    return { error: "Choose approve or reject." };
  }

  try {
    await setKycStatus(userId, decision);
    revalidatePath("/admin/kyc");
    return {
      success: decision === "APPROVED" ? "Identity approved." : "Identity rejected.",
    };
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return { error: error.userMessage };
    }
    return { error: "That decision could not be recorded." };
  }
}
