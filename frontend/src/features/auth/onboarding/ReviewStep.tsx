"use client";

import { Button } from "@/components/ui/form";
import type { OnboardingData, StepId } from "@/features/auth/onboarding/steps";
import { formatPhone } from "@/lib/phone";
import { lastFourOfSsn, maskedSsn } from "@/lib/ssn";
import { stateName } from "@/lib/us-states";

/**
 * Everything the customer entered, before it is submitted.
 *
 * Each section can be edited from here and returns straight back, so checking
 * one wrong digit does not mean walking through the remaining steps again.
 *
 * Two things are shown differently from how they were typed. The password is
 * not shown at all — it was entered twice and confirmed, and reprinting it on
 * screen serves nobody. The Social Security number is shown as its last four
 * digits, because those are the only part that will exist after this form is
 * submitted, so showing more would be showing something that is about to stop
 * being true.
 */
function formatDate(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return value;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return date.toLocaleDateString("en-US", {
    year: "numeric",
    month: "long",
    day: "numeric",
    timeZone: "UTC",
  });
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-0.5 py-1.5">
      <dt className="text-[0.8125rem] text-ink-muted">{label}</dt>
      <dd className="text-sm text-ink">{value || "—"}</dd>
    </div>
  );
}

function Section({
  title,
  step,
  onEdit,
  children,
}: {
  title: string;
  step: StepId;
  onEdit: (step: StepId) => void;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-[var(--radius-control)] border border-line p-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-ink">{title}</h3>
        <Button
          type="button"
          variant="ghost"
          onClick={() => onEdit(step)}
          className="h-8 px-2.5 text-[0.8125rem]"
        >
          Edit
          <span className="sr-only"> {title.toLowerCase()}</span>
        </Button>
      </div>
      <dl className="mt-2 divide-y divide-line">{children}</dl>
    </section>
  );
}

export function ReviewStep({
  data,
  onEdit,
}: {
  data: OnboardingData;
  onEdit: (step: StepId) => void;
}) {
  const fullName = [data.firstName, data.middleName, data.lastName].filter(Boolean).join(" ");

  return (
    <div className="space-y-4">
      <Section title="Sign-in" step="account" onEdit={onEdit}>
        <Row label="Username" value={data.username} />
        <Row label="Email address" value={data.email} />
        {/* The password is not reprinted here. It was entered and confirmed. */}
        <Row label="Password" value="Set" />
      </Section>

      <Section title="Personal" step="personal" onEdit={onEdit}>
        <Row label="Name" value={fullName} />
        <Row label="Date of birth" value={formatDate(data.dateOfBirth)} />
        <Row label="Phone" value={formatPhone(data.phone)} />
      </Section>

      <Section title="Address" step="address" onEdit={onEdit}>
        <Row label="Street" value={data.streetAddress} />
        {data.addressLine2 ? <Row label="Apt, suite or unit" value={data.addressLine2} /> : null}
        <Row label="City" value={data.city} />
        <Row label="State" value={stateName(data.state)} />
        <Row label="ZIP code" value={data.postalCode} />
      </Section>

      <Section title="Identity" step="identity" onEdit={onEdit}>
        <Row label="Social Security number" value={maskedSsn(lastFourOfSsn(data.ssn))} />
        <Row label="Terms" value={data.acceptedTerms ? "Accepted" : "Not accepted"} />
      </Section>
    </div>
  );
}
