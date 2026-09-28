"use client";

import { useActionState, useState } from "react";
import { Button, FormError, SuccessNote } from "@/components/ui/form";
import { setKycStatusAction, type KycFormState } from "@/features/kyc/actions";
import type { KycStatus } from "@/types/api";

const INITIAL: KycFormState = {};

/**
 * The identity decision for one customer. Underwriting reads this status, so it
 * is what lets a verified customer's credit application be decided by policy
 * rather than referred to a person.
 */
export function KycStatusControl({
  userId,
  status,
  isSelf,
}: {
  userId: number;
  status: KycStatus;
  isSelf: boolean;
}) {
  const [state, action, pending] = useActionState(setKycStatusAction, INITIAL);
  const [confirming, setConfirming] = useState<"APPROVED" | "REJECTED" | null>(null);

  if (isSelf) {
    return <p className="text-sm text-ink-subtle">You cannot decide your own identity check.</p>;
  }
  if (status === "APPROVED" || status === "REJECTED") {
    return (
      <p className="text-sm text-ink-subtle">
        Identity {status === "APPROVED" ? "approved" : "rejected"}. Credit applications use this
        decision.
      </p>
    );
  }

  return (
    <form action={action} className="grid gap-3">
      <input type="hidden" name="userId" value={userId} />
      <input type="hidden" name="decision" value={confirming ?? ""} />
      {state.error ? <FormError>{state.error}</FormError> : null}
      {state.success ? <SuccessNote>{state.success}</SuccessNote> : null}

      {confirming ? (
        <div className="grid gap-2">
          <p className="text-sm text-ink">
            {confirming === "APPROVED"
              ? "Approve this customer's identity? Their credit applications will be decided by policy."
              : "Reject this customer's identity? Their credit applications will be refused."}
          </p>
          <div className="flex flex-wrap gap-2">
            <Button type="submit" variant={confirming === "APPROVED" ? "primary" : "danger"} disabled={pending}>
              {pending ? "Saving…" : confirming === "APPROVED" ? "Confirm approval" : "Confirm rejection"}
            </Button>
            <Button type="button" variant="secondary" onClick={() => setConfirming(null)} disabled={pending}>
              Back
            </Button>
          </div>
        </div>
      ) : (
        <div className="flex flex-wrap gap-2">
          <Button type="button" onClick={() => setConfirming("APPROVED")}>
            Approve identity
          </Button>
          <Button type="button" variant="secondary" onClick={() => setConfirming("REJECTED")}>
            Reject identity
          </Button>
        </div>
      )}
      <p className="text-xs text-ink-subtle">
        Review the documents below first. Approving a document does not verify the customer; this
        does.
      </p>
    </form>
  );
}
