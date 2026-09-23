import type { Application, ReasonCode } from "@/types/api";

/**
 * What a customer is told, as opposed to what the bank recorded.
 *
 * The decision itself carries machine-readable reason codes and the exact
 * ratios they came from. None of that belongs on a customer's screen: a ratio
 * is the bank's working, and a code is for counting and filtering. What a
 * customer needs is what happened and what, if anything, they can do about it.
 *
 * Nothing here promises an outcome, and nothing quotes a threshold — publishing
 * "you needed 640" invites gaming the policy rather than understanding it.
 */
const CUSTOMER_SAFE: Record<ReasonCode, string> = {
  CREDIT_SCORE_BELOW_MINIMUM:
    "Your Northbank demo credit score is below what this product asks for.",
  DTI_ABOVE_POLICY:
    "Your existing monthly commitments are high relative to the income you told us about.",
  LTV_ABOVE_POLICY: "The amount requested is high relative to the value of the asset.",
  REQUEST_AMOUNT_ABOVE_POLICY: "The amount requested is more than this product lends.",
  TERM_NOT_SUPPORTED: "The repayment term requested is not one this product offers.",
  KYC_REVIEW_REQUIRED: "We still need to finish checking your identity.",
  KYC_REJECTED: "We were not able to verify your identity.",
  MANUAL_REVIEW_REQUIRED: "A member of our team is looking at this application.",
  INSUFFICIENT_INFORMATION: "We need a little more information before we can decide.",
};

export function reasonText(code: ReasonCode): string {
  return CUSTOMER_SAFE[code] ?? "A member of our team is looking at this application.";
}

/**
 * One line describing where an application has got to.
 *
 * Deliberately says nothing about a product for an application that is
 * provisioning: it has been asked for and not yet confirmed, and telling a
 * customer their card exists before the card service says so is the mistake the
 * whole lifecycle was rebuilt to stop.
 */
export function statusSummary(application: Application): string {
  switch (application.status) {
    case "SUBMITTED":
    case "UNDER_REVIEW":
      return "We are assessing your application.";
    case "MANUAL_REVIEW":
      return "A member of our team is reviewing your application.";
    case "OFFERED":
      return "We have made you an offer. It is yours to accept or decline.";
    case "ACCEPTED":
    case "PROVISIONING":
      return "You accepted the offer. We are setting the product up now.";
    case "PROVISIONED":
      return "Your product is ready.";
    case "DECLINED":
      return "You declined this offer.";
    case "REJECTED":
      return "We were not able to approve this application.";
    case "CANCELLED":
      return "This application was cancelled.";
    default:
      return "";
  }
}

/** Whether the customer still has a decision to make. */
export function awaitingCustomer(application: Application): boolean {
  return application.status === "OFFERED";
}
