"use server";

import { revalidatePath } from "next/cache";
import { reviewApplication } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import { requireStaffSession } from "@/lib/session";

export interface ReviewFormState {
  error?: string;
  success?: string;
}

/**
 * A reviewer's decision on a referred application.
 *
 * `requireStaffSession` keeps customers off the page; the backend's
 * `requireStaff` on the review endpoint is what refuses them.
 */
export async function reviewApplicationAction(
  _prev: ReviewFormState,
  formData: FormData,
): Promise<ReviewFormState> {
  await requireStaffSession();

  const id = Number(formData.get("applicationId"));
  const decision = String(formData.get("decision"));
  const notes = String(formData.get("reviewerNotes") ?? "").trim();
  const rawAmount = String(formData.get("approvedAmount") ?? "").trim();

  if (!Number.isFinite(id)) return { error: "That application could not be identified." };
  if (decision !== "APPROVE" && decision !== "REJECT") return { error: "Choose approve or reject." };
  if (decision === "REJECT" && notes.length === 0) {
    return { error: "Record why the application is being rejected." };
  }
  const approvedAmount = rawAmount === "" ? undefined : Number(rawAmount.replace(/,/g, ""));
  if (approvedAmount !== undefined && !(approvedAmount > 0)) {
    return { error: "An approved amount must be greater than zero." };
  }

  try {
    await reviewApplication(id, {
      decision,
      ...(decision === "APPROVE" && approvedAmount !== undefined ? { approvedAmount } : {}),
      ...(notes ? { reviewerNotes: notes } : {}),
    });
    revalidatePath(`/admin/applications/${id}`);
    revalidatePath("/admin/applications");
    return {
      success:
        decision === "APPROVE"
          ? "Approved. An offer has been made; the customer decides whether to accept it."
          : "Rejected. No offer will be made.",
    };
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) return { error: error.userMessage };
    return { error: "That decision could not be recorded." };
  }
}
