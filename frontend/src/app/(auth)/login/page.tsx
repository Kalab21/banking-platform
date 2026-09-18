import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { AuthCard } from "@/components/layout/AuthCard";
import { LoginForm } from "@/features/auth/LoginForm";
import { getSession } from "@/lib/session";

export const metadata: Metadata = { title: "Sign in" };

export default async function LoginPage() {
  // Already signed in — no reason to show the form again.
  if (await getSession()) redirect("/dashboard");

  // The heading lives in the form: it changes when the second factor is
  // requested, and the page has no way to know that has happened.
  return (
    <AuthCard width="form">
      <LoginForm />
    </AuthCard>
  );
}
