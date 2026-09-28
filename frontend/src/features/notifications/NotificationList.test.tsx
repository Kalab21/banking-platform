import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { NotificationList } from "./NotificationList";
import type { Notification } from "@/types/api";

vi.mock("@/features/notifications/actions", () => ({ markReadAction: vi.fn(), markAllReadAction: vi.fn() }));

// The shape notification-service actually sends: `read`, not `isRead`.
const wire = (read: boolean): Notification =>
  JSON.parse(
    JSON.stringify({
      id: 1, userId: 7, type: "OVERDRAFT_ALERT", title: "Overdraft Alert",
      message: "Your balance went below zero.", referenceId: null, referenceType: null,
      createdAt: "2026-09-27T19:11:58", read,
    }),
  );

describe("notification read state", () => {
  it("shows a read notification as read, with nothing left to mark", () => {
    render(<NotificationList notifications={[wire(true)]} />);
    expect(screen.queryByText("Unread")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /mark.*read/i })).not.toBeInTheDocument();
  });

  it("shows an unread notification as unread", () => {
    render(<NotificationList notifications={[wire(false)]} />);
    expect(screen.getByText("Unread")).toBeInTheDocument();
  });
});
