"use client";

import Link from "next/link";
import { useActionState, useId, useState } from "react";
import { ArrowRight, CheckCircle2, HelpCircle } from "lucide-react";
import { Button, FormError, MoneyField, SelectField } from "@/components/ui/form";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatCurrency } from "@/lib/format";
import type { MoneyAccountOption } from "@/features/transactions/money-account";
import { IDLE, type MoneyFormState } from "@/features/money/state";

/**
 * One payment towards a loan or a card, or receiving a loan's money.
 *
 * The same three steps as moving money: details, review, receipt. The review
 * step is where the operation is named — the id sent as the Idempotency-Key is
 * minted there and kept for every attempt at this payment, so retrying a
 * refused attempt is the same payment, not a second one.
 *
 * The component stays mounted when the page it sits on is refreshed after a
 * payment. That is deliberate: paying a loan off changes what the page offers,
 * and the receipt has to survive that change rather than vanish with the form.
 * `available` says whether the payment can be started now; a receipt or an
 * unresolved outcome is shown whatever it says.
 */

export interface AmountChoice {
  id: string;
  label: string;
  amount: number;
  hint?: string;
}

type Action = (prev: MoneyFormState, formData: FormData) => Promise<MoneyFormState>;

export interface ServicingPaymentProps {
  title: string;
  description: string;
  action: Action;
  /** Which loan or card, as the form field the action reads. */
  target: { name: "loanId" | "cardId"; id: number };
  currency: string;
  accounts: MoneyAccountOption[];
  choices: AmountChoice[];
  /** Offer an "Other amount" field alongside the fixed choices. */
  allowOther?: boolean;
  accountLabel?: string;
  confirmLabel: string;
  /** Shown on the review step, above the confirm button. */
  reviewNote?: string;
  /** False for the disbursement, which the backend makes once per loan by itself. */
  keyed?: boolean;
  available: boolean;
  /** Said instead of the form while `available` is false. Nothing is shown without it. */
  unavailable?: string;
  testId: string;
}

export function ServicingPayment(props: ServicingPaymentProps) {
  // Bumped to begin a new payment; remounting is what clears the last result.
  const [attempt, setAttempt] = useState(0);
  return <Flow key={attempt} {...props} onStartAgain={() => setAttempt((n) => n + 1)} />;
}

const OTHER = "other";

function Flow({
  title,
  description,
  action,
  target,
  currency,
  accounts,
  choices,
  allowOther = false,
  accountLabel = "Pay from",
  confirmLabel,
  reviewNote,
  keyed = true,
  available,
  unavailable,
  testId,
  onStartAgain,
}: ServicingPaymentProps & { onStartAgain: () => void }) {
  const [state, formAction, pending] = useActionState<MoneyFormState, FormData>(action, IDLE);
  const [step, setStep] = useState<"details" | "review">("details");
  const [choice, setChoice] = useState<string>(choices[0]?.id ?? (allowOther ? OTHER : ""));
  const [other, setOther] = useState("");
  const [accountId, setAccountId] = useState(accounts.length === 1 ? String(accounts[0].id) : "");
  const [operationId, setOperationId] = useState<string | null>(null);
  const groupId = useId();

  const outcome = state.status === "settled" ? state.outcome : undefined;
  const fields = state.status === "invalid" ? state.fields : undefined;
  const source = accounts.find((a) => String(a.id) === accountId);
  const picked = choices.find((c) => c.id === choice);
  const amountText = choice === OTHER ? other.trim() : picked ? picked.amount.toFixed(2) : "";
  const amount = Number(amountText) || 0;

  if (outcome?.kind === "succeeded") {
    return (
      <Card>
        <CardBody data-testid={`${testId}-receipt`}>
          <div className="flex items-start gap-3">
            <span
              aria-hidden="true"
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-positive-soft text-positive"
            >
              <CheckCircle2 className="h-5 w-5" />
            </span>
            <div className="min-w-0">
              <p role="status" className="text-base font-semibold text-ink">
                {outcome.message}
              </p>
              <p className="tabular mt-1 text-2xl font-semibold tracking-tight text-ink">
                {formatCurrency(outcome.amount ?? amount, currency)}
              </p>
            </div>
          </div>
          <dl className="mt-5 space-y-2 border-t border-line pt-4 text-sm">
            <Line label={keyed ? "From" : "Into"} value={source?.maskedNumber ?? "—"} />
            <Line label="Reference" value={outcome.reference} testId={`${testId}-reference`} />
          </dl>
          {available ? (
            <div className="mt-5">
              <Button type="button" variant="secondary" onClick={onStartAgain}>
                Make another payment
              </Button>
            </div>
          ) : null}
        </CardBody>
      </Card>
    );
  }

  if (outcome?.kind === "unknown") {
    return (
      <Card>
        <CardBody data-testid={`${testId}-unresolved`}>
          <div className="flex items-start gap-3">
            <span
              aria-hidden="true"
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-caution-soft text-caution"
            >
              <HelpCircle className="h-5 w-5" />
            </span>
            <div className="min-w-0">
              <p role="alert" className="text-base font-semibold text-ink">
                We could not confirm this payment
              </p>
              <p className="mt-1 text-sm text-ink-muted">{outcome.message}</p>
            </div>
          </div>
          {/* No way to send it again from here: that button might move the money twice. */}
          <div className="mt-5">
            <Link
              href="/transactions"
              className="inline-flex min-h-11 items-center rounded-[var(--radius-control)] bg-primary px-4 text-sm font-medium text-white hover:bg-primary-strong"
            >
              View transactions
            </Link>
          </div>
        </CardBody>
      </Card>
    );
  }

  if (!available) {
    if (!unavailable) return null;
    return (
      <Card>
        <CardHeader title={title} />
        <CardBody>
          <p className="text-sm text-ink-muted">{unavailable}</p>
        </CardBody>
      </Card>
    );
  }

  if (accounts.length === 0) {
    return (
      <Card>
        <CardHeader title={title} description={description} />
        <CardBody>
          <p className="text-sm text-ink-muted">
            You need an account in {currency} to do this.{" "}
            <Link href="/accounts" className="font-medium text-primary hover:underline">
              View accounts
            </Link>
          </p>
        </CardBody>
      </Card>
    );
  }

  const ready = accountId !== "" && amountText !== "" && (choice !== OTHER || amount > 0);

  function review() {
    if (keyed) setOperationId((current) => current ?? crypto.randomUUID());
    setStep("review");
  }

  return (
    <Card>
      <CardHeader title={title} description={description} />
      <CardBody>
        <form action={formAction} data-testid={`${testId}-form`} className="space-y-5" noValidate>
          <input type="hidden" name={target.name} value={target.id} />
          {keyed ? <input type="hidden" name="operationId" value={operationId ?? ""} /> : null}
          {outcome?.kind === "rejected" ? <FormError>{outcome.message}</FormError> : null}
          {fields?.amount ? <FormError>{fields.amount}</FormError> : null}

          {step === "details" ? (
            <>
              {choices.length + (allowOther ? 1 : 0) > 1 ? (
                <fieldset className="space-y-2">
                  <legend className="mb-1 text-sm font-medium text-ink">Amount</legend>
                  {choices.map((c) => (
                    <label
                      key={c.id}
                      className="flex min-h-11 cursor-pointer items-center justify-between gap-3 rounded-[var(--radius-control)] border border-line px-3 py-2 has-[:checked]:border-primary"
                    >
                      <span className="flex items-center gap-2">
                        <input
                          type="radio"
                          name={`${groupId}-choice`}
                          value={c.id}
                          checked={choice === c.id}
                          onChange={() => setChoice(c.id)}
                        />
                        <span className="text-sm text-ink">
                          {c.label}
                          {c.hint ? <span className="block text-xs text-ink-subtle">{c.hint}</span> : null}
                        </span>
                      </span>
                      <span className="tabular text-sm font-medium text-ink">
                        {formatCurrency(c.amount, currency)}
                      </span>
                    </label>
                  ))}
                  {allowOther ? (
                    <label className="flex min-h-11 cursor-pointer items-center gap-2 rounded-[var(--radius-control)] border border-line px-3 py-2 has-[:checked]:border-primary">
                      <input
                        type="radio"
                        name={`${groupId}-choice`}
                        value={OTHER}
                        checked={choice === OTHER}
                        onChange={() => setChoice(OTHER)}
                      />
                      <span className="text-sm text-ink">Other amount</span>
                    </label>
                  ) : null}
                </fieldset>
              ) : picked ? (
                <p className="text-sm text-ink">
                  {picked.label}:{" "}
                  <span className="tabular font-semibold">{formatCurrency(picked.amount, currency)}</span>
                  {picked.hint ? <span className="block text-xs text-ink-subtle">{picked.hint}</span> : null}
                </p>
              ) : null}

              {choice === OTHER ? (
                <MoneyField
                  label="Other amount"
                  name="otherAmount"
                  currency={currency}
                  value={other}
                  onChange={(e) => setOther(e.target.value)}
                />
              ) : null}

              <SelectField
                label={accountLabel}
                name="sourceAccountId"
                required
                value={accountId}
                onChange={(e) => setAccountId(e.target.value)}
                error={fields?.sourceAccountId}
              >
                <option value="">Select an account</option>
                {accounts.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.label}
                  </option>
                ))}
              </SelectField>

              <Button type="button" onClick={review} disabled={!ready}>
                Review
                <ArrowRight aria-hidden="true" className="ml-1.5 h-4 w-4" />
              </Button>
            </>
          ) : (
            <>
              {/* Exactly what was reviewed is what is sent. */}
              <input type="hidden" name="amount" value={amountText} />
              <input type="hidden" name="sourceAccountId" value={accountId} />
              <input type="hidden" name="currency" value={currency} />

              <div
                className="rounded-[var(--radius-card)] border border-line bg-sunken p-5"
                data-testid={`${testId}-review`}
              >
                <p className="text-xs font-semibold uppercase tracking-wide text-ink-subtle">Review</p>
                <p className="tabular mt-1 text-2xl font-semibold tracking-tight text-ink">
                  {formatCurrency(amount, currency)}
                </p>
                <dl className="mt-4 space-y-2 text-sm">
                  <Line label={accountLabel} value={source?.maskedNumber ?? "—"} />
                  {keyed && source ? (
                    <Line label="Available" value={formatCurrency(source.availableBalance, source.currency)} />
                  ) : null}
                </dl>
                {reviewNote ? <p className="mt-3 text-xs text-ink-subtle">{reviewNote}</p> : null}
              </div>

              <div className="flex flex-wrap gap-2">
                <Button type="submit" pending={pending} disabled={pending}>
                  {pending ? "Sending…" : confirmLabel}
                </Button>
                <Button type="button" variant="secondary" onClick={() => setStep("details")} disabled={pending}>
                  Back
                </Button>
              </div>
            </>
          )}
        </form>
      </CardBody>
    </Card>
  );
}

function Line({ label, value, testId }: { label: string; value: string; testId?: string }) {
  return (
    <div className="flex items-baseline justify-between gap-4">
      <dt className="text-ink-subtle">{label}</dt>
      <dd data-testid={testId} className="tabular min-w-0 truncate text-right font-medium text-ink">
        {value}
      </dd>
    </div>
  );
}
