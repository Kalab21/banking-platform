"use client";

import { useActionState } from "react";
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
export function OfferActions({ applicationId }: { applicationId: number }) {
  const [acceptState, accept, accepting] = useActionState<CreditFormState, FormData>(
    acceptOfferAction,
    {},
  );
  const [declineState, decline, declining] = useActionState<CreditFormState, FormData>(
    declineOfferAction,
    {},
  );

  const error = acceptState.error ?? declineState.error;

  return (
    <div className="grid gap-2">
      {error ? <FormError>{error}</FormError> : null}

      <div className="flex flex-wrap gap-2">
        <form action={accept}>
          <input type="hidden" name="applicationId" value={applicationId} />
          <Button type="submit" disabled={accepting || declining}>
            {accepting ? "Accepting…" : "Accept offer"}
          </Button>
        </form>

        <form action={decline}>
          <input type="hidden" name="applicationId" value={applicationId} />
          <Button type="submit" variant="secondary" disabled={accepting || declining}>
            {declining ? "Declining…" : "Decline"}
          </Button>
        </form>
      </div>

      <p className="text-xs text-[var(--text-muted)]">
        Accepting these terms creates the product. Declining closes this offer for good.
      </p>
    </div>
  );
}
