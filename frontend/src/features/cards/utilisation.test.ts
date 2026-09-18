import { describe, expect, it } from "vitest";
import { utilisation } from "@/features/cards/utilisation";
import { balanceProgress } from "@/features/loans/progress";

/**
 * The two arithmetic helpers behind the product pages.
 *
 * They are separate from their components precisely so these cases can be
 * stated: a limit of zero, a balance past its limit, a credit balance, and a
 * loan whose remaining balance exceeds what was borrowed. Each of those draws
 * something wrong — a bar past the end of its track, a negative width, NaN — if
 * it is handled where the markup is.
 */

describe("credit utilisation", () => {
  it("is the balance as a percentage of the limit", () => {
    expect(utilisation(2_500, 10_000).percent).toBe(25);
    expect(utilisation(10_000, 10_000).percent).toBe(100);
  });

  it("reports over-limit honestly rather than capping the number", () => {
    // The bar is clamped when it is drawn. The figure is not, because a card
    // that is over its limit should say so.
    const over = utilisation(11_000, 10_000);
    expect(over.percent).toBe(110);
    expect(over.tone).toBe("critical");
  });

  it("survives a zero limit without dividing by it", () => {
    const none = utilisation(500, 0);
    expect(none.percent).toBe(0);
    expect(Number.isNaN(none.percent)).toBe(false);
  });

  it("survives values that are not numbers", () => {
    expect(utilisation(Number.NaN, 10_000).percent).toBe(0);
    expect(utilisation(500, Number.POSITIVE_INFINITY).percent).toBe(0);
  });

  it("warns as the limit is approached, and only then", () => {
    expect(utilisation(5_000, 10_000).tone).toBe("primary");
    expect(utilisation(7_500, 10_000).tone).toBe("caution");
    expect(utilisation(10_500, 10_000).tone).toBe("critical");
  });

  it("reports an overpaid card as using none of its limit", () => {
    // A credit balance is money the customer is owed, not negative usage.
    // Flooring here is not the same choice as capping above: none of the limit
    // is genuinely in use, whereas being over it is a fact worth stating.
    expect(utilisation(-250, 10_000).percent).toBe(0);
    expect(utilisation(-250, 10_000).tone).toBe("primary");
  });
});

describe("loan balance progress", () => {
  it("measures how far the balance has come down from the principal", () => {
    const progress = balanceProgress(25_000, 8_450);
    expect(progress?.percent).toBe(66);
    expect(progress?.reduced).toBe(16_550);
  });

  it("is 100% for a loan that has been paid off", () => {
    expect(balanceProgress(25_000, 0)?.percent).toBe(100);
  });

  it("is 0% for a loan that has just been disbursed", () => {
    expect(balanceProgress(25_000, 25_000)?.percent).toBe(0);
  });

  it("does not go negative when the balance exceeds what was borrowed", () => {
    // Unpaid interest rolled into the balance would otherwise read as the loan
    // going backwards past zero.
    const progress = balanceProgress(25_000, 26_000);
    expect(progress?.percent).toBe(0);
    expect(progress?.reduced).toBe(0);
  });

  it("returns nothing at all when a percentage would be meaningless", () => {
    // No bar is better than a bar drawn from a guess.
    expect(balanceProgress(0, 0)).toBeNull();
    expect(balanceProgress(-100, 50)).toBeNull();
    expect(balanceProgress(Number.NaN, 50)).toBeNull();
  });
});
