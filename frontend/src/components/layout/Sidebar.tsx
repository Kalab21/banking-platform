"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";
import { cn } from "@/lib/cn";
import { isActivePath, navItemsForRole } from "@/lib/nav";
import { Wordmark } from "@/components/layout/Wordmark";
import type { Role } from "@/types/api";

/**
 * Primary navigation.
 *
 * Entries are filtered by role so staff tools do not clutter a customer's view.
 * This is presentation only — the backend enforces access on every staff route.
 */
export function Sidebar({ role, username }: { role: Role; username: string }) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const items = navItemsForRole(role);

  return (
    <>
      {/* Mobile bar */}
      <div className="flex items-center justify-between border-b border-line bg-surface px-4 py-3 lg:hidden">
        <Wordmark />
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
          aria-controls="primary-nav"
          className="rounded-md border border-line-strong px-3 py-1.5 text-sm font-medium text-ink"
        >
          {open ? "Close" : "Menu"}
        </button>
      </div>

      <nav
        id="primary-nav"
        aria-label="Primary"
        className={cn(
          "border-line bg-surface lg:block lg:w-60 lg:shrink-0 lg:border-r",
          open ? "block border-b" : "hidden",
        )}
      >
        <div className="hidden px-5 py-5 lg:block">
          <Wordmark />
        </div>

        <ul className="space-y-0.5 px-3 py-3">
          {items.map((item) => {
            const active = isActivePath(pathname, item.href);
            return (
              <li key={item.href}>
                <Link
                  href={item.href}
                  onClick={() => setOpen(false)}
                  aria-current={active ? "page" : undefined}
                  className={cn(
                    "block rounded-md px-3 py-2 text-sm transition-colors",
                    active
                      ? "bg-accent-soft font-medium text-accent"
                      : "text-ink-muted hover:bg-sunken hover:text-ink",
                  )}
                >
                  {item.label}
                </Link>
              </li>
            );
          })}
        </ul>

        <div className="border-t border-line px-5 py-4">
          <p className="text-xs text-ink-subtle">Signed in as</p>
          <p className="truncate text-sm font-medium text-ink">{username}</p>
          <p className="mt-0.5 text-xs text-ink-subtle">{role.toLowerCase()}</p>
        </div>
      </nav>
    </>
  );
}
