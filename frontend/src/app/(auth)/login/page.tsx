import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { LoginForm } from "@/features/auth/LoginForm";
import { getSession } from "@/lib/session";
import { Card, CardBody } from "@/components/ui/primitives";
import { Wordmark } from "@/components/layout/Wordmark";

export const metadata: Metadata = { title: "Sign in" };

export default async function LoginPage() {
  // Already signed in — no reason to show the form again.
  if (await getSession()) redirect("/dashboard");

  return (
    <div className="space-y-6">
      <div className="flex flex-col items-center gap-3">
        <Wordmark />
        <div className="text-center">
          <h1 className="text-xl font-semibold tracking-tight text-ink">Sign in to your account</h1>
          <p className="mt-1 text-sm text-ink-muted">Access balances, payments and statements.</p>
        </div>
      </div>

      <Card>
        <CardBody className="p-6">
          <LoginForm />
        </CardBody>
      </Card>
    </div>
  );
}
