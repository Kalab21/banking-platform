"use client";

import { useActionState } from "react";
import { Button, FormError, SuccessNote } from "@/components/ui/form";
import { setCardFreezeAction, type CardStatusFormState } from "@/features/cards/actions";

/**
 * Freeze, or lift a freeze the customer applied.
 *
 * Shown only for the two states a cardholder has authority over. A card the
 * bank blocked, defaulted or closed gets a line of text instead of a control —
 * offering a button that the backend would refuse is worse than offering none,
 * because it tells the customer they can do something they cannot.
 */
export function FreezeControl({ cardId, status }: { cardId: number; status: string }) {
  const [state, action, pending] = useActionState<CardStatusFormState, FormData>(
    setCardFreezeAction,
    {},
  );

  if (status !== "ACTIVE" && status !== "CUSTOMER_FROZEN") {
    return (
      <p className="text-sm text-[var(--text-muted)]">
        This card is not active. Please contact us about it — it is not something you can change
        here.
      </p>
    );
  }

  const freezing = status === "ACTIVE";

  return (
    <form action={action} className="grid gap-2">
      {state.error ? <FormError>{state.error}</FormError> : null}
      {state.success ? <SuccessNote>{state.success}</SuccessNote> : null}

      <input type="hidden" name="cardId" value={cardId} />
      <input type="hidden" name="intent" value={freezing ? "freeze" : "unfreeze"} />

      <div>
        <Button type="submit" variant="secondary" disabled={pending}>
          {pending ? "Working…" : freezing ? "Freeze card" : "Unfreeze card"}
        </Button>
      </div>

      <p className="text-xs text-[var(--text-muted)]">
        {freezing
          ? "A frozen card cannot be spent on. You can unfreeze it yourself at any time."
          : "You froze this card. Unfreezing makes it spendable again."}
      </p>
    </form>
  );
}
