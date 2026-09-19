import { ApiError, NetworkError } from "@/lib/api/errors";

/**
 * What the customer is told after a money-movement request.
 *
 * Three outcomes, not two. "It worked" and "it failed" are the easy ones; the
 * third is a request whose result the platform genuinely does not know, and
 * collapsing it into either of the others is how a customer ends up sending the
 * same money twice or believing money moved when it did not.
 *
 * The backend's contract is what decides which is which — see the idempotency
 * table in docs/DEVELOPMENT.md:
 *
 * | Backend                                             | Status | Moved money? |
 * |-----------------------------------------------------|--------|--------------|
 * | validation, insufficient funds, frozen account       | 400/422| definitely not |
 * | duplicate arrived while the first was still running  | 409    | not this one |
 * | key already used for a different request             | 409    | not this one |
 * | circuit open, the call never left transaction-service| 503    | definitely not |
 * | account-service reached, outcome never established   | 504    | **unknown** |
 * | replay of a key that settled unknown                 | 504    | **unknown** |
 * | transfer debited but the credit failed               | 500    | **partly, unreconciled** |
 */
export type MoneyOutcome =
  | { kind: "succeeded"; reference: string; message: string }
  | { kind: "rejected"; message: string }
  | { kind: "unknown"; message: string };

/**
 * Whether a failure proves nothing moved, or leaves it open.
 *
 * The conservative direction is deliberate: anything 5xx other than 503 is
 * treated as unknown. 503 is the one server status that carries a promise —
 * the circuit was open, so the call never left transaction-service and no
 * balance was touched. A bare 500 carries no such promise, and a transfer that
 * debited and failed to credit arrives as exactly that.
 */
export function classifyMoneyFailure(error: unknown, currency?: string): MoneyOutcome {
  if (error instanceof ApiError) {
    if (error.status === 504 || (error.status >= 500 && error.status !== 503)) {
      return { kind: "unknown", message: unknownMessage };
    }
    return { kind: "rejected", message: customerCopy(error.userMessage, currency) };
  }

  /*
   * A transport failure is the ambiguous case by definition: the request may
   * have been received and answered, with only the answer lost. The console
   * cannot tell that apart from a request that never arrived, so it does not
   * guess.
   */
  if (error instanceof NetworkError) {
    return { kind: "unknown", message: unknownMessage };
  }

  return { kind: "rejected", message: "That request could not be completed." };
}

/**
 * The two refusals a customer actually meets, in their language rather than
 * the service's.
 *
 * `ApiError.userMessage` prefers the backend's own wording, which is right
 * almost everywhere: the service knows why it said no. Money movement is the
 * exception, because these two sentences are written for a log line. A
 * customer who is told "Insufficient funds. Available: 164.01, Requested: 250"
 * has to work out which figure is theirs and what currency either is in, and
 * "Cannot transact on a FROZEN account" is a Java enum in the middle of a
 * sentence. Anything this does not recognise is passed through untouched — a
 * refusal the console does not understand is still the backend's to explain.
 */
const INSUFFICIENT_FUNDS = /^insufficient funds\.\s*available:\s*(-?[\d.]+),\s*requested:\s*(-?[\d.]+)/i;
const ACCOUNT_STATUS = /^cannot transact on an? ([A-Z_]+) account/i;
const CURRENCY_CODE = /^[A-Z]{3}$/;

function asMoney(value: number, currency: string): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: CURRENCY_CODE.test(currency) ? currency : "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value);
}

function customerCopy(message: string, currency = "USD"): string {
  const funds = INSUFFICIENT_FUNDS.exec(message);
  if (funds) {
    const available = Number(funds[1]);
    const requested = Number(funds[2]);
    if (!Number.isFinite(available) || !Number.isFinite(requested)) {
      return "That account does not have enough available money for this payment.";
    }
    return (
      `That account has ${asMoney(available, currency)} available and this payment is ` +
      `${asMoney(requested, currency)}. Enter a smaller amount, or move money into the ` +
      "account first."
    );
  }

  const status = ACCOUNT_STATUS.exec(message);
  if (status) {
    return (
      `That account is ${status[1].toLowerCase().replace(/_/g, " ")}, so money cannot move ` +
      "in or out of it. Contact us if you think that is wrong."
    );
  }

  return message;
}

/**
 * Deliberately not "try again".
 *
 * A retry here is a new logical operation, and a new logical operation against
 * a balance that may already have changed is the specific mistake this wording
 * exists to prevent. The customer is pointed at their own history instead,
 * which is the one place that settles the question.
 */
export const unknownMessage =
  "We could not confirm whether this went through. Do not send it again yet — " +
  "check your transaction history first to see whether it was applied.";
