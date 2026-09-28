"use client";

import { useActionState, useState } from "react";
import { Button, FormError } from "@/components/ui/form";
import { acceptOfferAction, declineOfferAction, type CreditFormState } from "@/features/credit/actions";

/**
 * Accept or decline, and nothing else.
 *
 * The only thing either form sends is which application. There is no input for
 * an amount, a rate or a term — a customer accepts the offer that was made, and
 * the terms are read from the stored offer by the backend. An offer the
 * customer could edit on the way past would not be an offer.
 */
export function OfferActions({ applicationId, terms }: { applicationId: number; terms?: string }) {
  const [acceptState, accept, accepting] = useActionState<CreditFormState, FormData>(
    acceptOfferAction,
    {},
  );
  const [declineState, decline, declining] = useActionState<CreditFormState, FormData>(
    declineOfferAction,
    {},
  );

  // Each answer is final, so each takes a second, explicit click: accepting
  // creates the loan or card, and declining closes the offer for good.
  const [confirming, setConfirming] = useState<"accept" | "decline" | null>(null);
  const busy = accepting || declining;

  const error = acceptState.error ?? declineState.error;

  return (
    <div className="grid gap-2">
      {error ? <FormError>{error}</FormError> : null}

      {confirming === "accept" ? (
        <form action={accept} className="grid gap-2 rounded-[var(--radius-control)] border border-line bg-sunken p-3">
          <input type="hidden" name="applicationId" value={applicationId} />
          <p className="text-sm text-ink">
            {terms ? <>Accept {terms}? </> : <>Accept these terms? </>}
            We will set up your product on exactly these terms.
          </p>
          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={busy}>
              {accepting ? "Accepting…" : "Confirm acceptance"}
            </Button>
            <Button type="button" variant="secondary" onClick={() => setConfirming(null)} disabled={busy}>
              Back
            </Button>
          </div>
        </form>
      ) : confirming === "decline" ? (
        <form action={decline} className="flex flex-wrap gap-2">
          <input type="hidden" name="applicationId" value={applicationId} />
          <Button type="submit" variant="danger" disabled={busy}>
            {declining ? "Declining…" : "Confirm decline"}
          </Button>
          <Button type="button" variant="secondary" onClick={() => setConfirming(null)} disabled={busy}>
            Keep offer
          </Button>
        </form>
      ) : (
        <div className="flex flex-wrap gap-2">
          <Button type="button" onClick={() => setConfirming("accept")} disabled={busy}>
            Accept offer
          </Button>
          <Button type="button" variant="secondary" onClick={() => setConfirming("decline")} disabled={busy}>
            Decline
          </Button>
        </div>
      )}

      <p className="text-xs text-ink-muted">
        Accepting these terms starts setting up your product. Declining closes this offer for good.
      </p>
    </div>
  );
}
