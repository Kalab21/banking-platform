"use client";

import { useActionState, useEffect, useRef, useState } from "react";
import {
  depositAction,
  transferAction,
  withdrawAction,
  type MoneyFormState,
} from "@/features/transactions/actions";
import { Button, FormError, MoneyField, SelectField, SuccessNote, TextField } from "@/components/ui/form";
import { Card, CardBody, CardHeader, EmptyState } from "@/components/ui/primitives";
import { formatCurrency } from "@/lib/format";
import type { MoneyAccountOption } from "@/features/transactions/money-account";

const INITIAL: MoneyFormState = {};

/*
 * These forms take `MoneyAccountOption`, never `Account`. The server builds the
 * labels; the account number does not cross into the browser. See
 * `money-account.ts` for why that boundary is drawn here rather than at render
 * time.
 */
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

/** Deposit and withdraw share a shape, so they share a component. */
function SingleAccountForm({
  kind,
  accounts,
}: {
  kind: "deposit" | "withdraw";
  accounts: MoneyAccountOption[];
}) {
  const action = kind === "deposit" ? depositAction : withdrawAction;
  const [state, formAction, pending] = useActionState(action, INITIAL);
  const formRef = useRef<HTMLFormElement>(null);

  // Clear the form once the deposit or withdrawal has actually settled, so the
  // amount cannot be resubmitted by accident. Done in an effect because a ref
  // must not be read during render.
  useEffect(() => {
    if (state.success) formRef.current?.reset();
  }, [state.success]);

  return (
    <form
      ref={formRef}
      action={formAction}
      data-testid={`${kind}-form`}
      className="space-y-4"
      noValidate
    >
      {state.error ? <FormError>{state.error}</FormError> : null}
      {state.success ? <SuccessNote>{state.success}</SuccessNote> : null}

      <SelectField
        label="Account"
        name="accountId"
        required
        error={state.fields?.accountId}
        disabled={pending}
      >
        <AccountOptions accounts={accounts} />
      </SelectField>

      <MoneyField
        label="Amount"
        name="amount"
        required
        error={state.fields?.amount}
        disabled={pending}
      />

      <TextField
        label="Description"
        name="description"
        hint="Optional"
        maxLength={255}
        error={state.fields?.description}
        disabled={pending}
      />

      <Button type="submit" pending={pending}>
        {pending
          ? kind === "deposit"
            ? "Depositing…"
            : "Withdrawing…"
          : kind === "deposit"
            ? "Deposit"
            : "Withdraw"}
      </Button>
    </form>
  );
}

/**
 * Transfer between two accounts.
 *
 * Transfers move money between parties, so this asks for explicit confirmation
 * before submitting rather than firing on the first click.
 */
function TransferForm({ accounts }: { accounts: MoneyAccountOption[] }) {
  const [state, formAction, pending] = useActionState(transferAction, INITIAL);
  const [confirming, setConfirming] = useState(false);
  const [amount, setAmount] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const fromAccount = accounts.find((a) => String(a.id) === from);
  const toAccount = accounts.find((a) => String(a.id) === to);
  const canConfirm = from !== "" && to !== "" && from !== to && amount.trim() !== "";

  return (
    <form action={formAction} data-testid="transfer-form" className="space-y-4" noValidate>
      {state.error ? <FormError>{state.error}</FormError> : null}
      {state.success ? <SuccessNote>{state.success}</SuccessNote> : null}

      <SelectField
        label="From"
        name="fromAccountId"
        required
        value={from}
        onChange={(e) => {
          setFrom(e.target.value);
          setConfirming(false);
        }}
        error={state.fields?.fromAccountId}
        disabled={pending}
      >
        <AccountOptions accounts={accounts} />
      </SelectField>

      <SelectField
        label="To"
        name="toAccountId"
        required
        value={to}
        onChange={(e) => {
          setTo(e.target.value);
          setConfirming(false);
        }}
        error={state.fields?.toAccountId}
        disabled={pending}
      >
        <AccountOptions accounts={accounts} />
      </SelectField>

      <MoneyField
        label="Amount"
        name="amount"
        required
        value={amount}
        onChange={(e) => {
          setAmount(e.target.value);
          setConfirming(false);
        }}
        error={state.fields?.amount}
        disabled={pending}
      />

      <TextField
        label="Description"
        name="description"
        hint="Optional"
        maxLength={255}
        error={state.fields?.description}
        disabled={pending}
      />

      {confirming ? (
        <div className="space-y-3 rounded-md border border-caution/40 bg-caution-soft px-4 py-3">
          <p className="text-sm text-caution">
            Send <strong>{formatCurrency(Number(amount) || 0, fromAccount?.currency ?? "USD")}</strong>{" "}
            from {fromAccount?.maskedNumber ?? "—"} to {toAccount?.maskedNumber ?? "—"}?
          </p>
          <div className="flex gap-2">
            <Button type="submit" pending={pending}>
              {pending ? "Sending…" : "Confirm transfer"}
            </Button>
            <Button
              type="button"
              variant="secondary"
              onClick={() => setConfirming(false)}
              disabled={pending}
            >
              Cancel
            </Button>
          </div>
        </div>
      ) : (
        <Button type="button" onClick={() => setConfirming(true)} disabled={!canConfirm}>
          Review transfer
        </Button>
      )}
    </form>
  );
}

export function MoneyForms({ accounts }: { accounts: MoneyAccountOption[] }) {
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

  return (
    <div className="grid gap-6 lg:grid-cols-3">
      <Card>
        <CardHeader title="Deposit" description="Add funds to one of your accounts." />
        <CardBody>
          <SingleAccountForm kind="deposit" accounts={accounts} />
        </CardBody>
      </Card>

      <Card>
        <CardHeader title="Withdraw" description="Take funds out, subject to available balance." />
        <CardBody>
          <SingleAccountForm kind="withdraw" accounts={accounts} />
        </CardBody>
      </Card>

      <Card>
        <CardHeader title="Transfer" description="Move money between your own accounts." />
        <CardBody>
          {accounts.length < 2 ? (
            <EmptyState
              title="Two accounts needed"
              description="A transfer needs a source and a destination account."
            />
          ) : (
            <TransferForm accounts={accounts} />
          )}
        </CardBody>
      </Card>
    </div>
  );
}
