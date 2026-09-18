/**
 * How much of a credit limit is in use.
 *
 * Separate from the component because the edge cases are the interesting part
 * and they deserve to be tested directly: a zero limit divides by zero, a
 * balance above its limit produces more than 100%, and a credit balance — a
 * customer who has overpaid — produces a negative.
 *
 * The percentage returned is the true one, rounded. Clamping belongs to the bar
 * that draws it, so the figure shown in text never disagrees with the account.
 */
export function utilisation(
  balance: number,
  limit: number,
): { percent: number; tone: "primary" | "caution" | "critical" } {
  if (!Number.isFinite(balance) || !Number.isFinite(limit) || limit <= 0) {
    return { percent: 0, tone: "primary" };
  }

  /*
   * Floored at zero but not capped at a hundred, and the asymmetry is the
   * point. A credit balance — a customer who has overpaid — means none of the
   * limit is in use, so "−3% used" would be a statement about nothing. Being
   * over the limit is a real condition the customer needs to see, so that
   * number is reported as it is and only the bar drawing it is clamped.
   */
  const percent = Math.max(Math.round((balance / limit) * 100), 0);

  // Over the limit is a problem; approaching it is worth noticing. Tone is a
  // supplement to the number, never the only way to read it.
  const tone = percent >= 100 ? "critical" : percent >= 75 ? "caution" : "primary";
  return { percent, tone };
}
