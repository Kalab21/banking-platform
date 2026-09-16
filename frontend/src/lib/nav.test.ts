import { describe, expect, it } from "vitest";
import { isActivePath, navItemsForRole } from "@/lib/nav";

describe("role-based navigation", () => {
  it("hides staff tools from a customer", () => {
    const labels = navItemsForRole("CUSTOMER").map((i) => i.label);

    expect(labels).toContain("Accounts");
    expect(labels).not.toContain("KYC Review");
    expect(labels).not.toContain("Fraud Alerts");
    expect(labels).not.toContain("Applications");
  });

  it("shows staff tools to an employee", () => {
    const labels = navItemsForRole("EMPLOYEE").map((i) => i.label);

    expect(labels).toContain("KYC Review");
    expect(labels).toContain("Fraud Alerts");
  });

  it("shows staff tools to an admin", () => {
    const labels = navItemsForRole("ADMIN").map((i) => i.label);

    expect(labels).toContain("KYC Review");
    expect(labels).toContain("Applications");
  });

  it("gives every role the customer-facing pages", () => {
    for (const role of ["CUSTOMER", "EMPLOYEE", "ADMIN"] as const) {
      const hrefs = navItemsForRole(role).map((i) => i.href);
      expect(hrefs).toContain("/dashboard");
      expect(hrefs).toContain("/accounts");
    }
  });
});

describe("isActivePath", () => {
  it("marks a nested route as active on its section", () => {
    expect(isActivePath("/accounts/42", "/accounts")).toBe(true);
  });

  it("does not mark the dashboard active from a nested route", () => {
    expect(isActivePath("/accounts/42", "/dashboard")).toBe(false);
  });

  it("does not match a partial path segment", () => {
    expect(isActivePath("/accounts-archive", "/accounts")).toBe(false);
  });
});
