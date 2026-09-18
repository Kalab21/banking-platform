import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { AuthCard } from "@/components/layout/AuthCard";
import { RegisterForm } from "@/features/auth/RegisterForm";
import { getSession } from "@/lib/session";

export const metadata: Metadata = { title: "Create account" };

export default async function RegisterPage() {
  if (await getSession()) redirect("/dashboard");

  return (
    <AuthCard width="wizard">
      <RegisterForm />
    </AuthCard>
  );
}
