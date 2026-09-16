import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { RegisterForm } from "@/features/auth/RegisterForm";
import { getSession } from "@/lib/session";

export const metadata: Metadata = { title: "Create account" };

export default async function RegisterPage() {
  if (await getSession()) redirect("/dashboard");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-ink">Create an account</h1>
        <p className="mt-1.5 text-sm text-ink-muted">
          Opens a demo customer profile on the platform.
        </p>
      </div>

      <RegisterForm />
    </div>
  );
}
