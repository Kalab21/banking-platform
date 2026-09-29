"use client";

import { useActionState, useState } from "react";
import { Button, FormError, MoneyField, SuccessNote, TextField } from "@/components/ui/form";
import { reviewApplicationAction, type ReviewFormState } from "@/features/admin/review-actions";

const INITIAL: ReviewFormState = {};

/**
 * Approve or reject a referred application.
 *
 * There is no Refer: the application is already referred, and the backend
 * refuses a move from MANUAL_REVIEW to itself. Approving makes an offer on the
 * policy's pricing; the customer still has to accept it.
 */
export function ReviewDecisionForm({
  applicationId,
  requestedAmount,
  currency,
  open,
  identityApproved,
}: {
  applicationId: number;
  requestedAmount: number | null;
  currency: string;
  /** Whether the application is still waiting on a reviewer. */
  open: boolean;
  /**
   * Whether the customer's identity check is approved. The backend refuses to
   * approve credit without it, so the button says so instead of failing.
   */
  identityApproved: boolean;
}) {
  const [state, action, pending] = useActionState(reviewApplicationAction, INITIAL);
  const [decision, setDecision] = useState<"APPROVE" | "REJECT" | null>(null);

  // Checked before `open`: recording a decision refreshes the page into the
  // decided state, and the confirmation has to survive that.
  if (state.success) return <SuccessNote>{state.success}</SuccessNote>;
  if (!open) {
    return <p className="text-sm text-ink-muted">This application has been decided. The decision history above is the record.</p>;
  }

  return (
    <form action={action} className="grid gap-4">
      <input type="hidden" name="applicationId" value={applicationId} />
      <input type="hidden" name="decision" value={decision ?? ""} />
      {state.error ? <FormError>{state.error}</FormError> : null}

      {decision === "APPROVE" && requestedAmount !== null ? (
        <MoneyField
          label="Amount to approve"
          name="approvedAmount"
          hint={`Up to the ${new Intl.NumberFormat("en-US", { style: "currency", currency }).format(requestedAmount)} requested. Leave blank to approve the full amount.`}
          placeholder={requestedAmount.toFixed(2)}
        />
      ) : null}
      {decision ? (
        <TextField
          label={decision === "REJECT" ? "Reason for rejection" : "Reviewer notes"}
          name="reviewerNotes"
          required={decision === "REJECT"}
          requiredMark={decision === "REJECT"}
          maxLength={1000}
          hint="Recorded with the decision."
        />
      ) : null}

      {/*
       * Keyed so React builds new buttons rather than reusing the ones just
       * clicked. Reused, the "Approve" button became the submit button during
       * its own click, and the browser then submitted the form: an approval
       * recorded before any amount or note was entered.
       */}
      {decision ? (
        <div key="confirm" className="flex flex-wrap gap-2">
          <Button type="submit" variant={decision === "REJECT" ? "danger" : "primary"} disabled={pending}>
            {pending ? "Recording…" : decision === "APPROVE" ? "Confirm approval" : "Confirm rejection"}
          </Button>
          <Button type="button" variant="secondary" onClick={() => setDecision(null)} disabled={pending}>
            Back
          </Button>
        </div>
      ) : (
        <div key="choose" className="flex flex-wrap gap-2">
          <Button type="button" onClick={() => setDecision("APPROVE")} disabled={!identityApproved}>
            Approve
          </Button>
          <Button type="button" variant="secondary" onClick={() => setDecision("REJECT")}>
            Reject
          </Button>
        </div>
      )}
      {!identityApproved ? (
        <p className="text-sm text-ink-muted">
          Approve the customer&apos;s identity before approving credit. You can still reject.
        </p>
      ) : null}
      <p className="text-xs text-ink-subtle">
        Approval makes an offer priced by the lending policy. It does not create a card or a loan:
        the customer reviews the terms and decides.
      </p>
    </form>
  );
}
