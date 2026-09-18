/**
 * How far a loan's balance has come down from the amount borrowed.
 *
 * Deliberately not called "principal paid". A repayment is split between
 * principal and interest, and the loan record exposes `principal` and
 * `remainingBalance` but not the split — so the only honest statement from
 * these two numbers is how much the balance has reduced. Calling that
 * "principal repaid" would attribute interest payments to the principal.
 *
 * Returns null when the figures cannot support a percentage at all: no
 * principal, or values that are not finite. A bar drawn from a guess is worse
 * than no bar.
 */
export function balanceProgress(
  principal: number,
  remainingBalance: number,
): { percent: number; reduced: number } | null {
  if (!Number.isFinite(principal) || !Number.isFinite(remainingBalance)) return null;
  if (principal <= 0) return null;

  const reduced = principal - remainingBalance;
  const percent = Math.round((reduced / principal) * 100);

  // A remaining balance above the original principal — unpaid interest rolled
  // in, say — would otherwise read as negative progress.
  return { percent: Math.min(Math.max(percent, 0), 100), reduced: Math.max(reduced, 0) };
}
