import { ArrowRight, Check, Clock } from "lucide-react";
import Link from "next/link";
import { buttonStyles } from "@/components/ui/button-styles";
import { maskedSsn } from "@/lib/ssn";

/**
 * The last step of onboarding: the account exists.
 *
 * Reads from the profile the server returns, not from what the browser had in
 * hand a moment ago. That makes this screen a confirmation of what was actually
 * stored rather than a restatement of what was typed — if a field did not
 * persist, this is where that would show.
 *
 * What it claims is limited to what happened. An account was created, a session
 * was issued, and identity details were submitted. Nothing has verified those
 * details: there is no verification provider behind this system, so the status
 * reads "pending review" and never "verified". A screen congratulating someone
 * on passing identity verification they had not passed would be the product
 * lying about itself.
 *
 * The next steps are the ones that exist. There is no confirmation email and no
 * welcome pack, so neither is promised.
 */
export function WelcomePanel({
  firstName,
  ssnLast4,
  identityStatus,
}: {
  firstName: string;
  ssnLast4: string | null;
  identityStatus: string | null;
}) {
  return (
    <div className="mx-auto max-w-xl py-6">
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
        {firstName ? `Welcome, ${firstName}. ` : ""}You are signed in and can start using Northbank
        now.
      </p>

      <div className="mt-6 rounded-[var(--radius-card)] border border-line bg-surface p-4">
        <div className="flex gap-3">
          <span aria-hidden="true" className="mt-0.5 text-ink-muted">
            <Clock className="h-[18px] w-[18px]" />
          </span>
          <div>
            <p className="text-sm font-medium text-ink">
              {identityStatus === "SUBMITTED"
                ? "Identity information submitted"
                : "Identity information not on file"}
            </p>
            <p className="mt-0.5 text-[0.8125rem] leading-relaxed text-ink-muted">
              Verification is pending review.
              {ssnLast4
                ? ` We kept only the last four digits of the number you gave (${maskedSsn(ssnLast4)}); the rest was not stored.`
                : ""}
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
        <Link href="/profile" className={buttonStyles("ghost", "lg", "w-full")}>
          Check the details on your profile
        </Link>
      </div>
    </div>
  );
}
