import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import AdminApplicationsPage from "./page";
import { getApplicationsByStatus } from "@/lib/api/banking";

vi.mock("server-only", () => ({}));
vi.mock("@/lib/session", () => ({ requireStaffSession: async () => ({ userId: 99 }) }));
vi.mock("@/lib/api/banking", () => ({ getApplicationsByStatus: vi.fn() }));

beforeEach(() => {
  vi.mocked(getApplicationsByStatus).mockReset().mockResolvedValue([]);
});

describe("staff application queue", () => {
  it("opens on manual review, the status that waits on a reviewer", async () => {
    render(await AdminApplicationsPage({ searchParams: Promise.resolve({}) }));
    expect(getApplicationsByStatus).toHaveBeenCalledWith("MANUAL_REVIEW");
    expect(screen.getByRole("link", { name: "Manual Review" })).toHaveAttribute("aria-current", "page");
    expect(screen.getAllByRole("link").map((l) => l.textContent)[0]).toBe("Manual Review");
  });

  it("honours a valid status filter and ignores an unknown one", async () => {
    render(await AdminApplicationsPage({ searchParams: Promise.resolve({ status: "OFFERED" }) }));
    expect(getApplicationsByStatus).toHaveBeenLastCalledWith("OFFERED");

    render(await AdminApplicationsPage({ searchParams: Promise.resolve({ status: "NOT_A_STATUS" }) }));
    expect(getApplicationsByStatus).toHaveBeenLastCalledWith("MANUAL_REVIEW");
  });
});
