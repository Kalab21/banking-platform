"use client";

import { useActionState } from "react";
import {
  confirmTwoFactorAction,
  disableTwoFactorAction,
  startTwoFactorAction,
  type TwoFactorState,
} from "@/features/profile/actions";
import { Button, FormError, SuccessNote, TextField } from "@/components/ui/form";
import { Badge, CardBody } from "@/components/ui/primitives";

const INITIAL: TwoFactorState = {};

function EnrolFlow() {
  const [startState, startAction, starting] = useActionState(startTwoFactorAction, INITIAL);
  const [confirmState, confirmAction, confirming] = useActionState(
    confirmTwoFactorAction,
    INITIAL,
  );

  const setup = confirmState.setup ?? startState.setup;

  if (confirmState.success) return <SuccessNote>{confirmState.success}</SuccessNote>;

  return (
    <div className="space-y-4">
      {startState.error ? <FormError>{startState.error}</FormError> : null}

      {!setup ? (
        <form action={startAction}>
          <Button type="submit" pending={starting}>
            {starting ? "Preparing…" : "Set up two-factor"}
          </Button>
        </form>
      ) : (
        <div className="space-y-4">
          <div className="rounded-md border border-line bg-sunken px-4 py-3">
            <p className="text-sm text-ink-muted">
              Add this secret to your authenticator app, then enter the 6-digit code it shows.
            </p>
            <code className="mt-2 block break-all font-mono text-sm text-ink">{setup.secret}</code>
          </div>

          <form action={confirmAction} className="space-y-3" noValidate>
            {confirmState.error ? <FormError>{confirmState.error}</FormError> : null}
            <TextField
              label="Authentication code"
              name="code"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              required
              error={confirmState.fields?.code}
              disabled={confirming}
            />
            <Button type="submit" pending={confirming}>
              {confirming ? "Verifying…" : "Enable two-factor"}
            </Button>
          </form>
        </div>
      )}
    </div>
  );
}

function DisableFlow() {
  const [state, action, pending] = useActionState(disableTwoFactorAction, INITIAL);

  if (state.success) return <SuccessNote>{state.success}</SuccessNote>;

  return (
    <form action={action} className="space-y-3" noValidate>
      {state.error ? <FormError>{state.error}</FormError> : null}
      <p className="text-sm text-ink-muted">
        Enter a current code from your authenticator app to turn two-factor off.
      </p>
      <TextField
        label="Authentication code"
        name="code"
        inputMode="numeric"
        autoComplete="one-time-code"
        maxLength={6}
        required
        error={state.fields?.code}
        disabled={pending}
      />
      <Button type="submit" variant="danger" pending={pending}>
        {pending ? "Disabling…" : "Disable two-factor"}
      </Button>
    </form>
  );
}

export function TwoFactorPanel({ enabled }: { enabled: boolean }) {
  return (
    <CardBody className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <span className="text-sm text-ink-muted">Status</span>
        <Badge tone={enabled ? "positive" : "caution"}>{enabled ? "Enabled" : "Not enabled"}</Badge>
      </div>

      {enabled ? <DisableFlow /> : <EnrolFlow />}

      <p className="border-t border-line pt-3 text-xs text-ink-subtle">
        Note: this platform enrols and verifies an authenticator secret, but sign-in does not yet
        prompt for a code. Enabling this records the second factor without adding a second step at
        login.
      </p>
    </CardBody>
  );
}
