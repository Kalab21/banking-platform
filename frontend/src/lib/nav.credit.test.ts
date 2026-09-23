import { describe, expect, it } from "vitest";
import { NAV_GROUPS, navGroupsForRole } from "@/lib/nav";

describe("navigation groups", () => {
  it("puts borrowing in its own family rather than under banking", () => {
    // Cards and loans sat beside the deposit accounts, which made credit look
    // like something you get once you already bank here.
    const banking = NAV_GROUPS.find((group) => group.label === "Banking");
    const credit = NAV_GROUPS.find((group) => group.label === "Borrow & credit");

    expect(credit).toBeDefined();
    expect(banking?.items.map((item) => item.href)).not.toContain("/cards");
    expect(banking?.items.map((item) => item.href)).not.toContain("/loans");
    expect(credit?.items.map((item) => item.href)).toEqual(
      expect.arrayContaining(["/credit", "/applications", "/cards", "/loans"]),
    );
  });

  it("offers a customer a way to reach the credit journey at all", () => {
    // The dead end this fixes: pages describing approved applications with
    // nothing anywhere that could produce one.
    const hrefs = navGroupsForRole("CUSTOMER").flatMap((group) =>
      group.items.map((item) => item.href),
    );
    expect(hrefs).toContain("/credit");
    expect(hrefs).toContain("/applications");
  });

  it("keeps staff tools out of a customer's navigation", () => {
    const hrefs = navGroupsForRole("CUSTOMER").flatMap((group) =>
      group.items.map((item) => item.href),
    );
    expect(hrefs.filter((href) => href.startsWith("/admin"))).toHaveLength(0);
  });
});
