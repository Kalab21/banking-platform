import { describe, expect, it } from "vitest";
import { initialsFor, isActivePath, navGroupsForRole, navItemsForRole } from "@/lib/nav";

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

  it("leaves a customer with no staff group at all, rather than an empty one", () => {
    // An empty "Staff tools" heading would tell a customer those tools exist
    // and announce a group with nothing in it to a screen reader.
    const groups = navGroupsForRole("CUSTOMER").map((g) => g.label);

    expect(groups).toEqual(["Banking", "Your account"]);
    expect(navGroupsForRole("CUSTOMER").every((g) => g.items.length > 0)).toBe(true);
  });

  it("gives staff the extra group without changing the customer ones", () => {
    const groups = navGroupsForRole("ADMIN");

    expect(groups.map((g) => g.label)).toEqual(["Banking", "Your account", "Staff tools"]);
    expect(groups.find((g) => g.label === "Staff tools")?.items).toHaveLength(3);
  });

  it("gives every entry an icon as well as a label", () => {
    // The icon is decorative and never the only thing naming a destination, but
    // a row missing one would break the alignment of the whole list.
    for (const item of navItemsForRole("ADMIN")) {
      expect(item.icon, item.label).toBeDefined();
      expect(item.label.length).toBeGreaterThan(0);
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

describe("initials", () => {
  it("takes one letter from each name", () => {
    expect(initialsFor("Avery", "Sinclair")).toBe("AS");
  });

  it("handles a missing surname without producing a stray letter", () => {
    expect(initialsFor("Avery", null)).toBe("A");
  });

  it("falls back to a dash rather than rendering an empty circle", () => {
    expect(initialsFor(null, null)).toBe("—");
    expect(initialsFor("  ", "")).toBe("—");
  });

  it("works for names outside the Latin alphabet", () => {
    expect(initialsFor("李", "明")).toBe("李明");
  });
});
