"use client";

import { AtSign, Phone, User } from "lucide-react";
import Link from "next/link";
import { useActionState, useState } from "react";
import { registerAction, type AuthFormState } from "@/features/auth/actions";
import { Button, FormError, PasswordField, TextField } from "@/components/ui/form";
import { PasswordRequirements } from "@/components/ui/PasswordRequirements";
import { formatPhone } from "@/lib/phone";

const INITIAL: AuthFormState = {};

/**
 * Account creation.
 *
 * The fields here are exactly the ones the registration API accepts, plus a
 * password confirmation that exists only in the browser. Identity details —
 * date of birth, address, verification — are collected during onboarding, which
 * is a separate flow with its own persistence; adding inputs for them here
 * would be collecting data nothing stores.
 */
export function RegisterForm() {
  const [state, action, pending] = useActionState(registerAction, INITIAL);

  /*
   * Held locally: the checklist reacts as the customer types, and both password
   * fields survive a rejected submission so that fixing an email typo does not
   * mean retyping the pair. This state lives in the browser only — the action
   * never echoes a password back, so nothing puts one into server-rendered HTML.
   */
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [phone, setPhone] = useState(state.values?.phone ?? "");

  return (
    <form action={action} className="space-y-6" noValidate>
      <div>
        <h1 className="text-[1.75rem] font-semibold tracking-tight text-ink">
          Create your Northbank account
        </h1>
        <p className="mt-2 text-[0.9375rem] leading-relaxed text-ink-muted">
          Start with your account details. Identity verification comes later, during onboarding.
        </p>
      </div>

      {state.error ? <FormError>{state.error}</FormError> : null}

      <div className="space-y-5">
        <div className="grid gap-5 sm:grid-cols-2">
          <TextField
            label="First name"
            name="firstName"
            autoComplete="given-name"
            required
            requiredMark
            defaultValue={state.values?.firstName}
            error={state.fields?.firstName}
            disabled={pending}
          />
          <TextField
            label="Last name"
            name="lastName"
            autoComplete="family-name"
            required
            requiredMark
            defaultValue={state.values?.lastName}
            error={state.fields?.lastName}
            disabled={pending}
          />
        </div>

        <TextField
          label="Email address"
          name="email"
          type="email"
          inputMode="email"
          autoComplete="email"
          required
          requiredMark
          icon={<AtSign aria-hidden="true" className="h-4 w-4" />}
          defaultValue={state.values?.email}
          error={state.fields?.email}
          disabled={pending}
        />

        <TextField
          label="Phone number"
          name="phone"
          type="tel"
          inputMode="tel"
          autoComplete="tel"
          hint="Optional"
          icon={<Phone aria-hidden="true" className="h-4 w-4" />}
          // Formatted as it is typed; the action normalises it back to digits
          // before the request, so what is stored is not a display string.
          value={phone}
          onChange={(e) => setPhone(formatPhone(e.target.value))}
          error={state.fields?.phone}
          disabled={pending}
        />

        <TextField
          label="Username"
          name="username"
          autoComplete="username"
          required
          requiredMark
          icon={<User aria-hidden="true" className="h-4 w-4" />}
          hint="3–50 characters"
          defaultValue={state.values?.username}
          error={state.fields?.username}
          disabled={pending}
        />

        <div>
          <PasswordField
            label="Password"
            name="password"
            autoComplete="new-password"
            required
            requiredMark
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={state.fields?.password}
            disabled={pending}
          />
          <PasswordRequirements value={password} />
        </div>

        <PasswordField
          label="Confirm password"
          name="confirmPassword"
          autoComplete="new-password"
          required
          requiredMark
          value={confirmPassword}
          onChange={(e) => setConfirmPassword(e.target.value)}
          error={state.fields?.confirmPassword}
          disabled={pending}
        />
      </div>

      <Button type="submit" size="lg" pending={pending} className="w-full">
        {pending ? "Creating account…" : "Create account"}
      </Button>

      <p className="text-center text-sm text-ink-muted">
        Already have an account?{" "}
        <Link href="/login" className="font-medium text-primary underline-offset-4 hover:underline">
          Sign in
        </Link>
      </p>
    </form>
  );
}
