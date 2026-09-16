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

  const decided = document.status !== "PENDING";

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

      {decided ? null : (
        <form action={action} className="mt-3 space-y-3">
          <input type="hidden" name="documentId" value={document.id} />
          <input type="hidden" name="decision" value={decision ?? ""} />

          {state.error ? <FormError>{state.error}</FormError> : null}
          {state.success ? <SuccessNote>{state.success}</SuccessNote> : null}

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
            <Button
              type="submit"
              pending={pending && decision === "APPROVED"}
              onClick={() => setDecision("APPROVED")}
              disabled={pending}
            >
              Approve
            </Button>
            {decision === "REJECTED" ? (
              <Button type="submit" variant="danger" pending={pending} disabled={pending}>
                Confirm rejection
              </Button>
            ) : (
              <Button
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
