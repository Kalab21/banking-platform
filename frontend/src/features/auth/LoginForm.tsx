"use client";

import Link from "next/link";
import { useActionState } from "react";
import { loginAction, type AuthFormState } from "@/features/auth/actions";
import { Button, FormError, TextField } from "@/components/ui/form";

const INITIAL: AuthFormState = {};

/**
 * Sign-in, in one or two steps.
 *
 * Accounts without a second factor complete in one submit. When the backend
 * answers `twoFactorRequired`, the form swaps to a code prompt and resubmits the
 * same credentials alongside the TOTP code — no session exists until that
 * second request succeeds.
 */
export function LoginForm() {
  const [state, action, pending] = useActionState(loginAction, INITIAL);
  const challenge = state.twoFactorRequired === true;

  return (
    <form action={action} className="space-y-5" noValidate>
      {state.error ? <FormError>{state.error}</FormError> : null}

      {challenge ? (
        <>
          <div className="rounded-md border border-line bg-sunken px-4 py-3">
            <p className="text-sm font-medium text-ink">Two-factor authentication</p>
            <p className="mt-0.5 text-sm text-ink-muted">
              Signing in as <span className="font-medium text-ink">{state.username}</span>. Enter
              the current code from your authenticator app.
            </p>
          </div>

          {/* Resubmitted so the second request carries the full credential set. */}
          <input type="hidden" name="username" value={state.username ?? ""} />

          <TextField
            label="Password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
            error={state.fields?.password}
            disabled={pending}
          />

          <TextField
            label="Authentication code"
            name="totpCode"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            required
            autoFocus
            hint="6 digits, refreshed every 30 seconds"
            error={state.fields?.totpCode}
            disabled={pending}
          />

          <Button type="submit" pending={pending} className="w-full">
            {pending ? "Verifying…" : "Verify and sign in"}
          </Button>
        </>
      ) : (
        <>
          <TextField
            label="Username"
            name="username"
            type="text"
            autoComplete="username"
            required
            autoFocus
            error={state.fields?.username}
            disabled={pending}
          />

          <TextField
            label="Password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
            error={state.fields?.password}
            disabled={pending}
          />

          <Button type="submit" pending={pending} className="w-full">
            {pending ? "Signing in…" : "Sign in"}
          </Button>
        </>
      )}

      <p className="text-center text-sm text-ink-muted">
        No account yet?{" "}
        <Link href="/register" className="font-medium text-accent underline-offset-2 hover:underline">
          Create one
        </Link>
      </p>
    </form>
  );
}
