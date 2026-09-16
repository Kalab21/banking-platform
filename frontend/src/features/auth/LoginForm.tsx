"use client";

import Link from "next/link";
import { useActionState } from "react";
import { loginAction, type AuthFormState } from "@/features/auth/actions";
import { Button, FormError, TextField } from "@/components/ui/form";

const INITIAL: AuthFormState = {};

export function LoginForm() {
  const [state, action, pending] = useActionState(loginAction, INITIAL);

  return (
    <form action={action} className="space-y-5" noValidate>
      {state.error ? <FormError>{state.error}</FormError> : null}

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

      <p className="text-center text-sm text-ink-muted">
        No account yet?{" "}
        <Link href="/register" className="font-medium text-accent underline-offset-2 hover:underline">
          Create one
        </Link>
      </p>
    </form>
  );
}
