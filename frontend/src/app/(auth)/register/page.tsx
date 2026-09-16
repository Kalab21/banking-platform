import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { RegisterForm } from "@/features/auth/RegisterForm";
import { getSession } from "@/lib/session";
import { Card, CardBody } from "@/components/ui/primitives";
import { Wordmark } from "@/components/layout/Wordmark";

export const metadata: Metadata = { title: "Create account" };

export default async function RegisterPage() {
  if (await getSession()) redirect("/dashboard");

  return (
    <div className="space-y-6">
      <div className="flex flex-col items-center gap-3">
        <Wordmark />
        <div className="text-center">
          <h1 className="text-xl font-semibold tracking-tight text-ink">Create an account</h1>
          <p className="mt-1 text-sm text-ink-muted">
            Opens a demo customer profile on the platform.
          </p>
        </div>
      </div>

      <Card>
        <CardBody className="p-6">
          <RegisterForm />
        </CardBody>
      </Card>
    </div>
  );
}
