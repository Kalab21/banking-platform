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
export function classifyMoneyFailure(error: unknown): MoneyOutcome {
  if (error instanceof ApiError) {
    if (error.status === 504 || (error.status >= 500 && error.status !== 503)) {
      return { kind: "unknown", message: unknownMessage };
    }
    return { kind: "rejected", message: error.userMessage };
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
