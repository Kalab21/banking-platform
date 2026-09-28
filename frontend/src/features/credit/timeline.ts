import { formatCurrency, formatPercent } from "@/lib/format";
import { productLabel } from "@/features/credit/products";
import { offerIsOpen, offerLapsed } from "@/features/credit/reasons";
import type { Application, Offer } from "@/types/api";

/**
 * An application's history, told only from what the platform recorded.
 *
 * Every dated step carries a timestamp the backend stored; a step with no
 * stored time is shown without one rather than with a guessed one. There is no
 * step the platform did not take — no "credit check", no "underwriter
 * assigned" — because nothing records those as separate events.
 */

export type StepState = "done" | "current" | "upcoming";

export interface TimelineStep {
  id: string;
  label: string;
  /** ISO timestamp from the backend, or null when none is stored. */
  at: string | null;
  state: StepState;
  detail?: string;
}

export function applicationTimeline(
  application: Application,
  offer?: Offer,
  now: Date = new Date(),
): TimelineStep[] {
  const product = productLabel(application.applicationType).toLowerCase();
  const steps: TimelineStep[] = [
    {
      id: "submitted",
      label: "Application submitted",
      at: application.appliedAt ?? application.createdAt,
      state: "done",
    },
  ];

  switch (application.status) {
    case "SUBMITTED":
    case "UNDER_REVIEW":
      steps.push({ id: "decision", label: "Being assessed", at: null, state: "current" });
      return steps;
    case "MANUAL_REVIEW":
      steps.push({
        id: "decision",
        label: "Referred to our team",
        at: application.reviewedAt,
        state: "current",
        detail: "A person will make the decision. You do not need to do anything yet.",
      });
      return steps;
    case "REJECTED":
      steps.push({ id: "decision", label: "Not approved", at: application.reviewedAt, state: "done" });
      return steps;
    case "CANCELLED":
      // No cancellation time is stored.
      steps.push({ id: "decision", label: "Cancelled", at: null, state: "done" });
      return steps;
    default:
      steps.push({ id: "decision", label: "Approved", at: application.reviewedAt, state: "done" });
  }

  if (!offer) return steps;
  steps.push({ id: "offer", label: "Offer made", at: offer.createdAt, state: "done" });

  if (offer.status === "ACCEPTED" || offer.acceptedAt) {
    steps.push({ id: "answer", label: "You accepted the offer", at: offer.acceptedAt, state: "done" });
  } else if (offer.status === "DECLINED" || offer.declinedAt) {
    steps.push({ id: "answer", label: "You declined the offer", at: offer.declinedAt, state: "done" });
    return steps;
  } else if (offer.status === "EXPIRED" || offerLapsed(application, offer, now)) {
    steps.push({ id: "answer", label: "Offer expired", at: offer.expiresAt, state: "done" });
    return steps;
  } else if (offerIsOpen(offer, now)) {
    steps.push({
      id: "answer",
      label: "Waiting for your answer",
      at: null,
      state: "current",
      detail: offer.expiresAt ? "The offer is open until the date shown with its terms." : undefined,
    });
    steps.push({ id: "product", label: `Your ${product} is set up`, at: null, state: "upcoming" });
    return steps;
  }

  if (application.status === "PROVISIONED") {
    // No provisioning time is stored; the product's own record has it.
    steps.push({ id: "product", label: `Your ${product} is ready`, at: null, state: "done" });
  } else {
    steps.push({ id: "product", label: `Setting up your ${product}`, at: null, state: "current" });
  }
  return steps;
}

/** One line naming the terms being accepted, from the stored offer only. */
export function offerSummary(offer: Offer): string | undefined {
  const apr = offer.apr !== null ? ` at ${formatPercent(offer.apr)} APR` : "";
  if (offer.creditLimit !== null) {
    return `a ${formatCurrency(offer.creditLimit, offer.currency)} credit limit${apr}`;
  }
  if (offer.approvedAmount !== null) {
    const term = offer.termMonths !== null ? ` over ${offer.termMonths} months` : "";
    return `${formatCurrency(offer.approvedAmount, offer.currency)}${term}${apr}`;
  }
  return undefined;
}
