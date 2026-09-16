"use client";

import Link from "next/link";
import { useActionState } from "react";
import { registerAction, type AuthFormState } from "@/features/auth/actions";
import { Button, FormError, TextField } from "@/components/ui/form";

const INITIAL: AuthFormState = {};

export function RegisterForm() {
  const [state, action, pending] = useActionState(registerAction, INITIAL);

  return (
    <form action={action} className="space-y-5" noValidate>
      {state.error ? <FormError>{state.error}</FormError> : null}

      <div className="grid gap-5 sm:grid-cols-2">
        <TextField
          label="First name"
          name="firstName"
          autoComplete="given-name"
          required
          error={state.fields?.firstName}
          disabled={pending}
        />
        <TextField
          label="Last name"
          name="lastName"
          autoComplete="family-name"
          required
          error={state.fields?.lastName}
          disabled={pending}
        />
      </div>

      <TextField
        label="Username"
        name="username"
        autoComplete="username"
        required
        error={state.fields?.username}
        disabled={pending}
      />

      <TextField
        label="Email"
        name="email"
        type="email"
        autoComplete="email"
        required
        error={state.fields?.email}
        disabled={pending}
      />

      <TextField
        label="Phone"
        name="phone"
        type="tel"
        autoComplete="tel"
        hint="Optional"
        error={state.fields?.phone}
        disabled={pending}
      />

      <TextField
        label="Password"
        name="password"
        type="password"
        autoComplete="new-password"
        required
        hint="At least 8 characters"
        error={state.fields?.password}
        disabled={pending}
      />

      <Button type="submit" pending={pending} className="w-full">
        {pending ? "Creating account…" : "Create account"}
      </Button>

      <p className="text-center text-sm text-ink-muted">
        Already registered?{" "}
        <Link href="/login" className="font-medium text-accent underline-offset-2 hover:underline">
          Sign in
        </Link>
      </p>
    </form>
  );
}
