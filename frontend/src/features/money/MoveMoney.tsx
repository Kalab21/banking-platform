"use client";

import Link from "next/link";
import { useActionState, useId, useState } from "react";
import { ArrowRight, CheckCircle2, HelpCircle } from "lucide-react";
import {
  Button,
  FormError,
  MoneyField,
  SelectField,
  TextField,
} from "@/components/ui/form";
import { Card, CardBody, CardHeader, EmptyState } from "@/components/ui/primitives";
import { formatCurrency } from "@/lib/format";
import type { MoneyAccountOption } from "@/features/transactions/money-account";
import { depositAction, transferAction, withdrawAction } from "@/features/money/actions";
import { IDLE, type MoneyFormState } from "@/features/money/state";

/**
 * Moving money, as a journey rather than three forms at once.
 *
 * The previous page put deposit, withdraw and transfer side by side, each one
 * armed and one click from executing. That reads like an operator console. A
 * customer does one of these things at a time, so this asks which, collects the
 * details, shows exactly what is about to happen, and only then offers a single
 * button that moves money.
 *
 * Three states per flow — details, review, receipt — and the review step is not
 * decoration. It is where the operation is named: the id sent as the
 * `Idempotency-Key` is minted when the customer reaches review and then stays
 * fixed for every attempt at that same payment. Retrying a refused request
 * reuses it. Only starting a new payment mints a new one.
 */

type Kind = "transfer" | "deposit" | "withdraw";

const TABS: { kind: Kind; label: string; description: string }[] = [
  { kind: "transfer", label: "Transfer", description: "Move money between your own accounts." },
  { kind: "deposit", label: "Deposit", description: "Record money coming into an account." },
  { kind: "withdraw", label: "Withdraw", description: "Take money out of an account." },
];

export function MoveMoney({ accounts }: { accounts: MoneyAccountOption[] }) {
  const [kind, setKind] = useState<Kind>("transfer");
  /*
   * Bumped to begin a new payment. `useActionState` keeps the last result, so
   * clearing the fields alone would leave the previous receipt on screen;
   * remounting the flow is what actually ends the finished operation.
   */
  const [attempt, setAttempt] = useState(0);
  const tabsId = useId();

  if (accounts.length === 0) {
    return (
      <Card>
        <CardHeader title="Move money" />
        <EmptyState
          title="You need an account first"
          description="Money movement becomes available once you hold at least one account."
        />
      </Card>
    );
  }

  const active = TABS.find((t) => t.kind === kind)!;

  return (
    <div className="space-y-5">
      {/*
       * A tablist rather than three links: switching between them is a change
       * of what this page is doing, not a navigation, and an in-progress
       * amount should not survive into a different kind of payment.
       */}
      <div role="tablist" aria-label="Choose an action" className="flex flex-wrap gap-2">
        {TABS.map((tab) => {
          const selected = tab.kind === kind;
          return (
            <button
              key={tab.kind}
              role="tab"
              type="button"
              id={`${tabsId}-${tab.kind}`}
              aria-selected={selected}
              aria-controls={`${tabsId}-panel`}
              onClick={() => setKind(tab.kind)}
              className={`min-h-11 rounded-[var(--radius-control)] border px-4 text-sm font-medium transition-colors ${
                selected
                  ? "border-primary bg-primary text-white"
                  : "border-line-strong bg-surface text-ink-muted hover:bg-sunken hover:text-ink"
              }`}
            >
              {tab.label}
            </button>
          );
        })}
      </div>

      <div id={`${tabsId}-panel`} role="tabpanel" aria-labelledby={`${tabsId}-${kind}`}>
        {/*
         * Keyed by kind so switching tabs discards the half-filled form and
         * its operation id, rather than carrying an amount across.
         */}
        <MoneyFlow
          key={`${kind}-${attempt}`}
          kind={kind}
          label={active.label}
          description={active.description}
          accounts={accounts}
          onStartAgain={() => setAttempt((n) => n + 1)}
        />
      </div>
    </div>
  );
}

type Step = "details" | "review";

interface Details {
  fromAccountId: string;
  toAccountId: string;
  amount: string;
  description: string;
}

const EMPTY: Details = { fromAccountId: "", toAccountId: "", amount: "", description: "" };

function MoneyFlow({
  kind,
  label,
  description,
  accounts,
  onStartAgain,
}: {
  kind: Kind;
  label: string;
  description: string;
  accounts: MoneyAccountOption[];
  onStartAgain: () => void;
}) {
  const action =
    kind === "deposit" ? depositAction : kind === "withdraw" ? withdrawAction : transferAction;

  const [state, formAction, pending] = useActionState<MoneyFormState, FormData>(action, IDLE);
  const [step, setStep] = useState<Step>("details");
  const [details, setDetails] = useState<Details>(EMPTY);

  /*
   * One id per logical operation, minted in the event handler that opens the
   * review step and then held in state so every attempt at this same payment
   * carries it. A refused attempt keeps it — that is what makes a retry the
   * same operation rather than a second one. Only starting over clears it.
   */
  const [operationId, setOperationId] = useState<string | null>(null);

  function review() {
    setOperationId((current) => current ?? crypto.randomUUID());
    setStep("review");
  }

  const source = accounts.find((a) => String(a.id) === details.fromAccountId);
  const destination = accounts.find((a) => String(a.id) === details.toAccountId);
  const single = kind !== "transfer";
  const account = single ? source : undefined;
  const currency = source?.currency ?? accounts[0]?.currency ?? "USD";
  const amount = Number(details.amount) || 0;

  const fields = state.status === "invalid" ? state.fields : undefined;
  const outcome = state.status === "settled" ? state.outcome : undefined;

  if (outcome?.kind === "succeeded") {
    return (
      <Receipt
        label={label}
        reference={outcome.reference}
        message={outcome.message}
        amount={amount}
        currency={currency}
        source={single ? account : source}
        destination={single ? undefined : destination}
        onStartAgain={onStartAgain}
      />
    );
  }

  if (outcome?.kind === "unknown") {
    return <Unresolved message={outcome.message} />;
  }

  const ready =
    details.amount.trim() !== "" &&
    (single
      ? details.fromAccountId !== ""
      : details.fromAccountId !== "" &&
        details.toAccountId !== "" &&
        details.fromAccountId !== details.toAccountId);

  return (
    <Card>
      <CardHeader title={label} description={description} />
      <CardBody>
        <form action={formAction} data-testid={`${kind}-form`} className="space-y-5" noValidate>
          <input type="hidden" name="operationId" value={operationId ?? ""} />

          {outcome?.kind === "rejected" ? <FormError>{outcome.message}</FormError> : null}

          {step === "details" ? (
            <>
              <SelectField
                label={single ? "Account" : "From"}
                name={single ? "accountId" : "fromAccountId"}
                required
                value={details.fromAccountId}
                onChange={(e) => setDetails({ ...details, fromAccountId: e.target.value })}
                error={fields?.accountId ?? fields?.fromAccountId}
              >
                <AccountOptions accounts={accounts} />
              </SelectField>

              {!single ? (
                <SelectField
                  label="To"
                  name="toAccountId"
                  required
                  value={details.toAccountId}
                  onChange={(e) => setDetails({ ...details, toAccountId: e.target.value })}
                  error={fields?.toAccountId}
                >
                  <AccountOptions accounts={accounts} />
                </SelectField>
              ) : null}

              <MoneyField
                label="Amount"
                name="amount"
                required
                currency={currency}
                value={details.amount}
                onChange={(e) => setDetails({ ...details, amount: e.target.value })}
                error={fields?.amount}
              />

              <TextField
                label="Description"
                name="description"
                hint="Optional"
                maxLength={255}
                value={details.description}
                onChange={(e) => setDetails({ ...details, description: e.target.value })}
                error={fields?.description}
              />

              <Button type="button" onClick={review} disabled={!ready}>
                Review {label.toLowerCase()}
                <ArrowRight aria-hidden="true" className="ml-1.5 h-4 w-4" />
              </Button>
            </>
          ) : (
            <>
              {/*
               * The details are carried as hidden inputs so the review step
               * submits exactly what was reviewed. Nothing on this screen can
               * change the numbers below it.
               */}
              <input type="hidden" name={single ? "accountId" : "fromAccountId"} value={details.fromAccountId} />
              {!single ? <input type="hidden" name="toAccountId" value={details.toAccountId} /> : null}
              <input type="hidden" name="amount" value={details.amount} />
              <input type="hidden" name="description" value={details.description} />

              <ReviewPanel
                kind={kind}
                amount={amount}
                currency={currency}
                source={source}
                destination={destination}
                description={details.description}
              />

              <div className="flex flex-wrap gap-2">
                <Button type="submit" pending={pending} disabled={pending}>
                  {pending ? "Sending…" : `Confirm ${label.toLowerCase()}`}
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() => setStep("details")}
                  disabled={pending}
                >
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

function AccountOptions({ accounts }: { accounts: MoneyAccountOption[] }) {
  return (
    <>
      <option value="">Select an account</option>
      {accounts.map((a) => (
        <option key={a.id} value={a.id}>
          {a.label}
        </option>
      ))}
    </>
  );
}

/** What is about to happen, in the customer's own terms and never unmasked. */
function ReviewPanel({
  kind,
  amount,
  currency,
  source,
  destination,
  description,
}: {
  kind: Kind;
  amount: number;
  currency: string;
  source?: MoneyAccountOption;
  destination?: MoneyAccountOption;
  description: string;
}) {
  return (
    <div className="rounded-[var(--radius-card)] border border-line bg-sunken p-5" data-testid="review-panel">
      <p className="text-xs font-semibold uppercase tracking-wide text-ink-subtle">Review</p>
      <p className="tabular mt-1 text-2xl font-semibold tracking-tight text-ink">
        {formatCurrency(amount, currency)}
      </p>

      <dl className="mt-4 space-y-2 text-sm">
        <Line label={kind === "deposit" ? "Into" : "From"} value={source?.maskedNumber ?? "—"} />
        {kind === "transfer" ? <Line label="To" value={destination?.maskedNumber ?? "—"} /> : null}
        {kind === "withdraw" && source ? (
          <Line label="Available" value={formatCurrency(source.availableBalance, source.currency)} />
        ) : null}
        {description.trim() ? <Line label="Description" value={description.trim()} /> : null}
      </dl>
    </div>
  );
}

function Line({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-4">
      <dt className="text-ink-subtle">{label}</dt>
      <dd className="tabular min-w-0 truncate text-right font-medium text-ink">{value}</dd>
    </div>
  );
}

function Receipt({
  label,
  reference,
  message,
  amount,
  currency,
  source,
  destination,
  onStartAgain,
}: {
  label: string;
  reference: string;
  message: string;
  amount: number;
  currency: string;
  source?: MoneyAccountOption;
  destination?: MoneyAccountOption;
  onStartAgain: () => void;
}) {
  return (
    <Card>
      <CardBody data-testid="receipt">
        <div className="flex items-start gap-3">
          <span
            aria-hidden="true"
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-positive-soft text-positive"
          >
            <CheckCircle2 className="h-5 w-5" />
          </span>
          <div className="min-w-0">
            {/*
             * role="status" so the outcome is announced rather than only
             * appearing, which is the whole point of a confirmation.
             */}
            <p role="status" className="text-base font-semibold text-ink">
              {message}
            </p>
            <p className="tabular mt-1 text-2xl font-semibold tracking-tight text-ink">
              {formatCurrency(amount, currency)}
            </p>
          </div>
        </div>

        <dl className="mt-5 space-y-2 border-t border-line pt-4 text-sm">
          <Line label={destination ? "From" : "Account"} value={source?.maskedNumber ?? "—"} />
          {destination ? <Line label="To" value={destination.maskedNumber} /> : null}
          {/* The backend's own reference. Nothing here is invented. */}
          <Line label="Reference" value={reference} />
        </dl>

        <div className="mt-5 flex flex-wrap gap-2">
          <Link
            href="/transactions"
            className="inline-flex min-h-11 items-center rounded-[var(--radius-control)] bg-primary px-4 text-sm font-medium text-white hover:bg-primary-strong"
          >
            View transactions
          </Link>
          <Button type="button" variant="secondary" onClick={onStartAgain}>
            Make another {label.toLowerCase()}
          </Button>
        </div>
      </CardBody>
    </Card>
  );
}

/**
 * The outcome nobody knows.
 *
 * Deliberately offers no way to send the request again. The customer is shown
 * their history instead, because that is the only thing that answers the
 * question, and a button here would be a button that might move the money a
 * second time.
 */
function Unresolved({ message }: { message: string }) {
  return (
    <Card>
      <CardBody data-testid="unresolved">
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
            <p className="mt-1 text-sm text-ink-muted">{message}</p>
          </div>
        </div>

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
