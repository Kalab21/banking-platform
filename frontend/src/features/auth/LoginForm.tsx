"use client";

import { KeyRound, User } from "lucide-react";
import Link from "next/link";
import { useActionState, useEffect, useRef } from "react";
import { loginAction, type AuthFormState } from "@/features/auth/actions";
import { Button, FormError, PasswordField, TextField } from "@/components/ui/form";

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

  const challengeHeading = useRef<HTMLHeadingElement>(null);

  /*
   * The page changes what it is asking for without navigating, so focus has to
   * be moved deliberately. Without this a screen-reader user submits a password
   * and is left with focus on a button that no longer exists, with no
   * indication that a code is now wanted.
   */
  useEffect(() => {
    if (challenge) challengeHeading.current?.focus();
  }, [challenge]);

  if (challenge) {
    return (
      <form action={action} className="space-y-6" noValidate>
        <div>
          <span
            aria-hidden="true"
            className="mb-5 inline-flex h-11 w-11 items-center justify-center rounded-xl bg-primary-soft text-primary"
          >
            <KeyRound className="h-5 w-5" />
          </span>
          <h1
            ref={challengeHeading}
            tabIndex={-1}
            className="text-[1.75rem] font-semibold tracking-tight text-ink outline-none"
          >
            Verify it&apos;s you
          </h1>
          <p className="mt-2 text-[0.9375rem] leading-relaxed text-ink-muted">
            Enter the 6-digit code from your authenticator app to finish signing in as{" "}
            <span className="font-medium text-ink">{state.username}</span>.
          </p>
        </div>

        {state.error ? <FormError>{state.error}</FormError> : null}

        {/* Resubmitted so the second request carries the full credential set. */}
        <input type="hidden" name="username" value={state.username ?? ""} />

        <PasswordField
          label="Password"
          name="password"
          autoComplete="current-password"
          required
          requiredMark
          error={state.fields?.password}
          disabled={pending}
        />

        <TextField
          label="Authentication code"
          name="totpCode"
          inputMode="numeric"
          pattern="[0-9]*"
          autoComplete="one-time-code"
          maxLength={6}
          required
          requiredMark
          autoFocus
          hint="6 digits, refreshed every 30 seconds"
          error={state.fields?.totpCode}
          disabled={pending}
          className="tabular text-center text-xl tracking-[0.5em]"
        />

        <Button type="submit" size="lg" pending={pending} className="w-full">
          {pending ? "Verifying…" : "Verify"}
        </Button>
      </form>
    );
  }

  return (
    <form action={action} className="space-y-6" noValidate>
      <div>
        <h1 className="text-[1.75rem] font-semibold tracking-tight text-ink">Welcome back</h1>
        <p className="mt-2 text-[0.9375rem] leading-relaxed text-ink-muted">
          Sign in to manage your accounts securely.
        </p>
      </div>

      {state.error ? <FormError>{state.error}</FormError> : null}

      <div className="space-y-5">
        <TextField
          label="Username"
          name="username"
          type="text"
          autoComplete="username"
          required
          requiredMark
          autoFocus
          icon={<User aria-hidden="true" className="h-4 w-4" />}
          // Kept across a rejected attempt so only the password is retyped.
          defaultValue={state.username}
          error={state.fields?.username}
          disabled={pending}
        />

        <PasswordField
          label="Password"
          name="password"
          autoComplete="current-password"
          required
          requiredMark
          error={state.fields?.password}
          disabled={pending}
        />
      </div>

      <Button type="submit" size="lg" pending={pending} className="w-full">
        {pending ? "Signing in…" : "Sign in"}
      </Button>

      <p className="text-center text-sm text-ink-muted">
        New to Northbank?{" "}
        <Link
          href="/register"
          className="font-medium text-primary underline-offset-4 hover:underline"
        >
          Create an account
        </Link>
      </p>
    </form>
  );
}
