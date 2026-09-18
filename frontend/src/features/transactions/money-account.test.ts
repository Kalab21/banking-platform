import { describe, expect, it } from "vitest";
import { toMoneyAccountOptions } from "@/features/transactions/money-account";
import type { Account } from "@/types/api";

/**
 * The Server → Client boundary for money movement.
 *
 * The money forms are a Client Component, so whatever the page hands them is
 * serialised into the RSC payload that ships inside the HTML — readable in
 * view-source regardless of what the rendered labels say. This suite is the
 * cheap half of the guard: it asserts the shape that crosses the boundary
 * carries no account number and nothing else the form does not need. The live
 * suite asserts the same property against the real payload a browser receives.
 */

function account(overrides: Partial<Account> = {}): Account {
  return {
    id: 68,
    accountNumber: "BA260918132024",
    userId: 130,
    accountType: "CHECKING",
    balance: 11_131.99,
    availableBalance: 11_631.99,
    currency: "USD",
    status: "ACTIVE",
    interestRate: 0,
    overdraftLimit: 500,
    overdraftBalance: 0,
    createdAt: "2026-09-18T02:27:55.032609",
    ...overrides,
  } as Account;
}

describe("the account view the money forms receive", () => {
  it("carries the id, a ready-made label, the masked number and the currency", () => {
    const [option] = toMoneyAccountOptions([account()]);

    expect(option).toEqual({
      id: 68,
      label: "Checking ••••2024 — $11,131.99",
      maskedNumber: "••••2024",
      currency: "USD",
    });
  });

  it("never carries the account number, under any key", () => {
    const options = toMoneyAccountOptions([
      account(),
      account({ id: 69, accountNumber: "BA260918399716", accountType: "SAVINGS", balance: 16_714.18 }),
    ]);

    // Serialised, because that is the form the browser actually receives: a
    // field added to the option type later cannot smuggle the number back in
    // without failing here.
    const wire = JSON.stringify(options);
    for (const number of ["BA260918132024", "BA260918399716"]) {
      expect(wire).not.toContain(number);
      expect(wire).toContain(`••••${number.slice(-4)}`);
    }

    for (const option of options) {
      expect(Object.keys(option).sort()).toEqual(["currency", "id", "label", "maskedNumber"]);
    }
  });

  it("keeps the id, because the operation cannot be submitted without one", () => {
    // Not a secret: the gateway re-checks ownership on every request, so an id
    // in the payload grants nothing on its own.
    expect(toMoneyAccountOptions([account()])[0].id).toBe(68);
  });

  it("leaves a four-character number alone, which is already only its last four", () => {
    const [option] = toMoneyAccountOptions([account({ accountNumber: "1234" })]);
    expect(option.maskedNumber).toBe("1234");
  });

  it("handles a customer with no accounts", () => {
    expect(toMoneyAccountOptions([])).toEqual([]);
  });
});
