"use client";

import { useActionState, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowLeft, ArrowRight } from "lucide-react";
import { Button, FormError, MoneyField, SelectField, TextField } from "@/components/ui/form";
import { applyForCreditAction, type CreditFormState } from "@/features/credit/actions";
import type { CreditProduct } from "@/features/credit/products";
import {
  FIELDS,
  initialValues,
  reviewLines,
  stepsFor,
  validateStep,
  type StepId,
  type Values,
} from "@/features/credit/apply-steps";

/**
 * Applying for credit as three short steps: what you need, your finances, and
 * a last look at everything before it is sent.
 *
 * It asks only what the customer can answer. There is no field for a credit
 * score, a rate, a limit or a tier — those are the bank's answers — and the
 * server action reads named fields rather than the whole form, so a field
 * added here later still would not get through.
 *
 * Nothing is sent until the review step. Each step checks its own answers so
 * a mistake is caught where it was made; the backend checks them all again.
 */
export function ApplyWizard({ product }: { product: CreditProduct }) {
  const [state, formAction, pending] = useActionState<CreditFormState, FormData>(
    applyForCreditAction,
    {},
  );
  const router = useRouter();
  const steps = stepsFor(product);
  const [stepIndex, setStepIndex] = useState(0);
  const [values, setValues] = useState<Values>(() => initialValues(product));
  const [errors, setErrors] = useState<Values>({});
  const heading = useRef<HTMLHeadingElement>(null);
  const step = steps[stepIndex];

  // Answered straight away — offered, referred or refused — so the customer is
  // taken to the application's own page, where that answer is.
  useEffect(() => {
    if (state.applicationId) router.push(`/applications/${state.applicationId}`);
  }, [state.applicationId, router]);

  // Moving between steps moves focus to the new step's heading, so a screen
  // reader announces where the customer now is.
  const moved = useRef(false);
  useEffect(() => {
    if (moved.current) heading.current?.focus();
    moved.current = true;
  }, [stepIndex]);

  const set = (name: string) => (event: { target: { value: string } }) =>
    setValues((current) => ({ ...current, [name]: event.target.value }));

  function goTo(id: StepId) {
    setErrors({});
    setStepIndex(steps.findIndex((s) => s.id === id));
  }

  function next() {
    const found = validateStep(product, step.id, values);
    setErrors(found);
    if (Object.keys(found).length === 0) setStepIndex((i) => i + 1);
  }

  return (
    <div className="grid gap-5">
      <ol aria-label="Application steps" className="flex flex-wrap gap-x-5 gap-y-2 text-sm">
        {steps.map((s, i) => (
          <li
            key={s.id}
            aria-current={i === stepIndex ? "step" : undefined}
            className={i === stepIndex ? "font-semibold text-ink" : i < stepIndex ? "text-ink-muted" : "text-ink-subtle"}
          >
            <span className="tabular">{i + 1}.</span> {s.title}
          </li>
        ))}
      </ol>

      <h2 ref={heading} tabIndex={-1} className="text-base font-semibold text-ink outline-none">
        Step {stepIndex + 1} of {steps.length}: {step.title}
      </h2>

      {state.error ? <FormError>{state.error}</FormError> : null}

      {step.id === "need" ? (
        <div className="grid gap-4">
          {product.type === "CREDIT_CARD" ? (
            <p className="text-sm text-ink-muted">
              You do not choose a limit or a rate. If we can offer you a card, we will tell you the
              limit, the tier and the APR, and they will be yours to accept or decline.
            </p>
          ) : null}
          {product.asks.amount ? (
            <MoneyField
              label="How much would you like to borrow?"
              name="requestedAmount"
              requiredMark
              value={values.requestedAmount}
              onChange={set("requestedAmount")}
              error={errors.requestedAmount}
              placeholder="10000.00"
            />
          ) : null}
          {product.asks.term && product.terms ? (
            <SelectField
              label="Over how long?"
              name="termMonths"
              requiredMark
              value={values.termMonths}
              onChange={set("termMonths")}
              error={errors.termMonths}
            >
              {product.terms.map((months) => (
                <option key={months} value={months}>
                  {months} months
                </option>
              ))}
            </SelectField>
          ) : null}
          {product.asks.asset ? (
            <MoneyField
              label={product.type === "MORTGAGE" ? "Value of the property" : "Value of the vehicle"}
              name="assetValue"
              requiredMark
              value={values.assetValue}
              onChange={set("assetValue")}
              error={errors.assetValue}
              placeholder="250000.00"
            />
          ) : null}
          {product.asks.downPayment ? (
            <MoneyField
              label="Deposit you are putting down"
              name="downPayment"
              value={values.downPayment}
              onChange={set("downPayment")}
              error={errors.downPayment}
              placeholder="25000.00"
              hint="Leave blank if you are not putting anything down."
            />
          ) : null}
          {product.asks.purpose ? (
            <TextField
              label="What is it for?"
              name="purpose"
              requiredMark={product.type === "PERSONAL_LOAN"}
              hint={product.type === "PERSONAL_LOAN" ? undefined : "Optional"}
              value={values.purpose}
              onChange={set("purpose")}
              error={errors.purpose}
              placeholder="Home improvement"
              maxLength={200}
            />
          ) : null}
        </div>
      ) : null}

      {step.id === "finances" ? (
        <div className="grid gap-4">
          <MoneyField
            label="Your annual income before tax"
            name="annualIncome"
            requiredMark
            value={values.annualIncome}
            onChange={set("annualIncome")}
            error={errors.annualIncome}
            placeholder="90000.00"
          />
          <MoneyField
            label="What you already pay each month towards other debts"
            name="monthlyDebtObligations"
            requiredMark
            value={values.monthlyDebtObligations}
            onChange={set("monthlyDebtObligations")}
            error={errors.monthlyDebtObligations}
            placeholder="450.00"
            hint="Loans, cards and other regular credit commitments. Enter 0 if you have none."
          />
        </div>
      ) : null}

      {step.id === "review" ? (
        <form action={formAction} className="grid gap-4">
          <input type="hidden" name="applicationType" value={product.type} />
          {FIELDS.map((name) => (
            <input key={name} type="hidden" name={name} value={values[name].replace(/,/g, "")} />
          ))}

          <dl className="divide-y divide-line rounded-[var(--radius-card)] border border-line" data-testid="application-review">
            {reviewLines(product, values).map((line) => (
              <div key={line.label} className="flex flex-wrap items-baseline justify-between gap-3 px-4 py-3">
                <dt className="text-sm text-ink-subtle">{line.label}</dt>
                <dd className="flex items-baseline gap-3 text-sm font-medium text-ink">
                  <span className="tabular break-all">{line.value}</span>
                  <button
                    type="button"
                    className="text-xs font-medium text-primary hover:underline"
                    onClick={() => goTo(line.step)}
                    aria-label={`Change ${line.label.toLowerCase()}`}
                  >
                    Change
                  </button>
                </dd>
              </div>
            ))}
          </dl>

          <p className="text-xs text-ink-muted">
            Submitting does not guarantee an offer. A demo policy decides using what you have told us
            and your Northbank demo credit score; some applications go to a person to decide. If we
            can lend, we will tell you the amount, the rate and the term, and they will be yours to
            accept or decline.
          </p>

          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={pending}>
              {pending ? "Submitting…" : "Submit application"}
            </Button>
            <Button type="button" variant="secondary" onClick={() => setStepIndex((i) => i - 1)} disabled={pending}>
              <ArrowLeft aria-hidden="true" className="mr-1.5 h-4 w-4" />
              Back
            </Button>
          </div>
        </form>
      ) : (
        <div className="flex flex-wrap gap-2">
          <Button type="button" onClick={next}>
            Continue
            <ArrowRight aria-hidden="true" className="ml-1.5 h-4 w-4" />
          </Button>
          {stepIndex > 0 ? (
            <Button type="button" variant="secondary" onClick={() => setStepIndex((i) => i - 1)}>
              <ArrowLeft aria-hidden="true" className="mr-1.5 h-4 w-4" />
              Back
            </Button>
          ) : null}
        </div>
      )}
    </div>
  );
}
