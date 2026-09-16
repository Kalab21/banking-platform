"use client";

import { useActionState } from "react";
import { logoutAction } from "@/features/auth/actions";
import { Button } from "@/components/ui/form";

async function signOut(): Promise<null> {
  await logoutAction();
  return null;
}

export function SignOutButton() {
  const [, action, pending] = useActionState(signOut, null);

  return (
    <form action={action}>
      <Button type="submit" variant="secondary" pending={pending}>
        {pending ? "Signing out…" : "Sign out"}
      </Button>
    </form>
  );
}
