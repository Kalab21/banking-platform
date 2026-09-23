"use client";

import { useActionState } from "react";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { Button, FormError, MoneyField, SelectField, SuccessNote, TextField } from "@/components/ui/form";
import { applyForCreditAction, type CreditFormState } from "@/features/credit/actions";
import type { CreditProduct } from "@/features/credit/products";

/**
 * A product-specific application form.
 *
 * It asks only what the customer can answer: what they want, over how long,
 * what they earn and what they already owe. There is no field for a credit
 * score, a rate, a limit or a tier, because those are the bank's answers — and
 * the server action reads named fields rather than the whole form, so adding
 * one to this markup later still would not get it through.
 *
 * The term is a choice among the terms the product actually offers. A free-text
 * term would let a customer ask for something that will only be refused, which
 * is a worse experience than not offering it.
 */
export function ApplyForm({ product }: { product: CreditProduct }) {
  const [state, formAction, pending] = useActionState<CreditFormState, FormData>(
    applyForCreditAction,
    {},
  );
  const router = useRouter();

  // A submitted application is answered immediately — approved, referred or
  // refused — so the customer is taken to where that answer is.
  useEffect(() => {
    if (state.applicationId) {
      router.push("/applications");
    }
  }, [state.applicationId, router]);

  return (
    <form action={formAction} className="grid gap-4">
      <input type="hidden" name="applicationType" value={product.type} />

      {state.error ? <FormError>{state.error}</FormError> : null}
      {state.success ? <SuccessNote>{state.success}</SuccessNote> : null}

      {product.asks.amount ? (
        <MoneyField
          label="How much would you like to borrow?"
          name="requestedAmount"
          requiredMark
          placeholder="10000.00"
        />
      ) : null}

      {product.asks.term && product.terms ? (
        <SelectField label="Over how long?" name="termMonths" requiredMark>
          {product.terms.map((months) => (
            <option key={months} value={months}>
              {months} months
            </option>
          ))}
        </SelectField>
      ) : null}

      {product.asks.asset ? (
        <MoneyField
          label={
            product.type === "MORTGAGE" ? "Value of the property" : "Value of the vehicle"
          }
          name="assetValue"
          requiredMark
          placeholder="250000.00"
        />
      ) : null}

      {product.asks.downPayment ? (
        <MoneyField
          label="Deposit you are putting down"
          name="downPayment"
          placeholder="25000.00"
          hint="Leave blank if you are not putting anything down."
        />
      ) : null}

      <MoneyField
        label="Your annual income before tax"
        name="annualIncome"
        requiredMark
        placeholder="90000.00"
      />

      <MoneyField
        label="What you already pay each month towards other debts"
        name="monthlyDebtObligations"
        requiredMark
        placeholder="450.00"
        hint="Loans, cards and other regular credit commitments."
      />

      {product.asks.purpose ? (
        <TextField
          label="What is it for?"
          name="purpose"
          placeholder="Home improvement"
          maxLength={200}
        />
      ) : null}

      <div className="pt-2">
        <Button type="submit" disabled={pending}>
          {pending ? "Submitting…" : "Submit application"}
        </Button>
      </div>

      <p className="text-xs text-[var(--text-muted)]">
        Submitting does not guarantee an offer. If we can lend, we will tell you the amount, the
        rate and the term, and it will be yours to accept or decline.
      </p>
    </form>
  );
}
