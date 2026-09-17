"use client";

import { ArrowRight, Check, Clock } from "lucide-react";
import Link from "next/link";
import { buttonStyles } from "@/components/ui/form";
import { maskedSsn } from "@/lib/ssn";

/**
 * The last step: the account exists.
 *
 * What is claimed here is limited to what actually happened. An account was
 * created, a session was issued, and identity details were submitted. Nothing
 * has verified those details — there is no verification provider behind this
 * system — so this says "submitted" and "pending review", never "verified".
 * A screen that congratulated someone on passing identity verification they had
 * not passed would be a lie told by the product about itself.
 *
 * The next steps are the ones that exist. There is no emailed confirmation and
 * no welcome pack, so neither is promised.
 */
export function OnboardingComplete({
  name,
  ssnLast4,
}: {
  name: string;
  ssnLast4: string;
}) {
  return (
    <div>
      <div className="flex justify-center">
        <span
          aria-hidden="true"
          className="flex h-12 w-12 items-center justify-center rounded-full bg-positive/10 text-positive"
        >
          <Check className="h-6 w-6" />
        </span>
      </div>

      <h1 className="mt-5 text-center text-[1.75rem] font-semibold tracking-tight text-ink">
        Your account is open
      </h1>
      <p className="mx-auto mt-2 max-w-sm text-center text-[0.9375rem] leading-relaxed text-ink-muted">
        {name ? `Welcome, ${name}. ` : ""}You are signed in and can start using Northbank now.
      </p>

      <div className="mt-6 rounded-[var(--radius-control)] border border-line bg-surface-subtle p-4">
        <div className="flex gap-3">
          <span aria-hidden="true" className="mt-0.5 text-ink-muted">
            <Clock className="h-[18px] w-[18px]" />
          </span>
          <div>
            <p className="text-sm font-medium text-ink">Identity information submitted</p>
            <p className="mt-0.5 text-[0.8125rem] leading-relaxed text-ink-muted">
              Verification is pending review. We have kept only the last four digits of the number
              you entered{ssnLast4 ? ` (${maskedSsn(ssnLast4)})` : ""}; the rest was not stored.
            </p>
          </div>
        </div>
      </div>

      <div className="mt-6 space-y-3">
        <Link href="/dashboard" className={buttonStyles("primary", "lg", "w-full")}>
          Go to your dashboard
          <ArrowRight aria-hidden="true" className="h-4 w-4" />
        </Link>
        <Link href="/accounts" className={buttonStyles("secondary", "lg", "w-full")}>
          Open your first account
        </Link>
      </div>
    </div>
  );
}
