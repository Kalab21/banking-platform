"use client";

import { useActionState, useState } from "react";
import { reviewKycAction, type KycFormState } from "@/features/kyc/actions";
import { Button, FormError, SuccessNote, TextField } from "@/components/ui/form";
import { Badge } from "@/components/ui/primitives";
import { formatDateTime, humanise } from "@/lib/format";
import type { KycDocument } from "@/types/api";

const INITIAL: KycFormState = {};

function ReviewRow({ document }: { document: KycDocument }) {
  const [state, action, pending] = useActionState(reviewKycAction, INITIAL);
  const [decision, setDecision] = useState<"APPROVED" | "REJECTED" | null>(null);

  // SUBMITTED and UNDER_REVIEW are awaiting a decision. This compared against
  // PENDING, a status documents never have, so the approve and reject buttons
  // never appeared at all.
  const decided = document.status === "APPROVED" || document.status === "REJECTED";

  return (
    <li className="px-5 py-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-medium text-ink">{humanise(document.documentType)}</p>
          <p className="font-mono text-xs text-ink-subtle">{document.documentRef}</p>
          <p className="mt-1 text-xs text-ink-subtle">
            Submitted {formatDateTime(document.createdAt)}
          </p>
        </div>
        <Badge
          tone={
            document.status === "APPROVED"
              ? "positive"
              : document.status === "REJECTED"
                ? "critical"
                : "caution"
          }
        >
          {humanise(document.status)}
        </Badge>
      </div>

      {document.rejectionReason ? (
        <p className="mt-2 text-sm text-critical">Reason: {document.rejectionReason}</p>
      ) : null}

      {/*
       * Outside the form: a decision refreshes the page into the decided
       * state, which removes the form, and the confirmation went with it.
       */}
      {state.success ? (
        <div className="mt-3">
          <SuccessNote>{state.success}</SuccessNote>
        </div>
      ) : null}

      {decided ? null : (
        <form action={action} className="mt-3 space-y-3">
          <input type="hidden" name="documentId" value={document.id} />

          {state.error ? <FormError>{state.error}</FormError> : null}

          {decision === "REJECTED" ? (
            <TextField
              label="Reason for rejection"
              name="rejectionReason"
              required
              hint="Shown to the customer, so make it actionable"
              disabled={pending}
            />
          ) : null}

          <div className="flex flex-wrap gap-2">
            {/*
             * The decision travels as the submitting button's own value. It
             * used to be a hidden input set from state in the click handler,
             * which the submission could read before the update landed.
             */}
            <Button
              type="submit"
              name="decision"
              value="APPROVED"
              pending={pending && decision !== "REJECTED"}
              disabled={pending}
            >
              Approve
            </Button>
            {/*
             * Keyed apart: reusing the clicked "Reject" button as the submit
             * button made the browser submit on that first click, before a
             * reason could be typed.
             */}
            {decision === "REJECTED" ? (
              <Button
                key="confirm-reject"
                type="submit"
                name="decision"
                value="REJECTED"
                variant="danger"
                pending={pending}
                disabled={pending}
              >
                Confirm rejection
              </Button>
            ) : (
              <Button
                key="reject"
                type="button"
                variant="secondary"
                onClick={() => setDecision("REJECTED")}
                disabled={pending}
              >
                Reject
              </Button>
            )}
          </div>
        </form>
      )}
    </li>
  );
}

export function KycReviewList({ documents }: { documents: KycDocument[] }) {
  return (
    <ul className="divide-y divide-line">
      {documents.map((d) => (
        <ReviewRow key={d.id} document={d} />
      ))}
    </ul>
  );
}
