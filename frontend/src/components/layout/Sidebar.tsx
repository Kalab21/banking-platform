"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useId, useRef, useState } from "react";
import { Menu, X } from "lucide-react";
import { cn } from "@/lib/cn";
import { initialsFor, isActivePath, navGroupsForRole } from "@/lib/nav";
import { Wordmark } from "@/components/layout/Wordmark";
import { SignOutButton } from "@/components/layout/SignOutButton";
import type { Role } from "@/types/api";

/**
 * Primary navigation.
 *
 * One component, two presentations. On a wide screen it is a permanent column;
 * on a phone the same list becomes a drawer over the page, because a permanent
 * column would leave no room for the content it navigates to.
 *
 * Entries are filtered by role so staff tools do not clutter a customer's view.
 * That is presentation: the backend enforces access on every staff route, and a
 * hidden link stops nobody from typing the URL.
 */
export function Sidebar({
  role,
  username,
  firstName,
  lastName,
}: {
  role: Role;
  username: string;
  firstName?: string | null;
  lastName?: string | null;
}) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const navId = useId();
  const toggleRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  const groups = navGroupsForRole(role);
  const displayName = [firstName, lastName].filter(Boolean).join(" ") || username;

  /*
   * Escape closes, and focus goes back to the control that opened it — landing
   * focus at the top of the document instead would make the next Tab restart
   * the whole page.
   */
  useEffect(() => {
    if (!open) return;

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setOpen(false);
      toggleRef.current?.focus();
    };

    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open]);

  // Focus moves into the drawer when it opens, so a keyboard or screen-reader
  // user is inside the thing that just appeared rather than behind it.
  useEffect(() => {
    if (open) panelRef.current?.focus();
  }, [open]);

  const navigation = (
    <nav aria-label="Primary" className="flex min-h-0 flex-1 flex-col">
      <div className="min-h-0 flex-1 overflow-y-auto px-3 py-4">
        {groups.map((group) => (
          <div key={group.label} className="mb-5 last:mb-0">
            <h2 className="px-3 pb-2 text-[0.6875rem] font-semibold uppercase tracking-wider text-ink-subtle">
              {group.label}
            </h2>
            <ul className="space-y-0.5">
              {group.items.map((item) => {
                const active = isActivePath(pathname, item.href);
                const Icon = item.icon;
                return (
                  <li key={item.href}>
                    <Link
                      href={item.href}
                      // Closes the drawer on the way out. Without this the new
                      // page loads underneath a panel still covering it.
                      onClick={() => setOpen(false)}
                      aria-current={active ? "page" : undefined}
                      className={cn(
                        "flex min-h-11 items-center gap-3 rounded-[var(--radius-control)] px-3 text-sm transition-colors",
                        active
                          ? "bg-primary-soft font-semibold text-primary"
                          : "text-ink-muted hover:bg-sunken hover:text-ink",
                      )}
                    >
                      <Icon
                        aria-hidden="true"
                        className={cn("h-[18px] w-[18px] shrink-0", active ? "text-primary" : "text-ink-subtle")}
                      />
                      <span className="truncate">{item.label}</span>
                    </Link>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </div>

      <div className="border-t border-line px-4 py-4">
        <div className="flex items-center gap-3">
          <span
            aria-hidden="true"
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-navy text-xs font-semibold text-white"
          >
            {initialsFor(firstName, lastName)}
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-medium text-ink">{displayName}</p>
            <p className="text-xs capitalize text-ink-subtle">{role.toLowerCase()}</p>
          </div>
        </div>
        <div className="mt-3">
          <SignOutButton />
        </div>
      </div>
    </nav>
  );

  return (
    <>
      {/* Phone and tablet: an app bar, with the navigation behind it. */}
      <div className="sticky top-0 z-30 flex items-center justify-between border-b border-line bg-surface px-4 py-2.5 lg:hidden">
        <Wordmark />
        <button
          ref={toggleRef}
          type="button"
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
          aria-controls={navId}
          className="inline-flex h-11 w-11 items-center justify-center rounded-[var(--radius-control)] border border-line-strong text-ink"
        >
          {open ? (
            <X aria-hidden="true" className="h-5 w-5" />
          ) : (
            <Menu aria-hidden="true" className="h-5 w-5" />
          )}
          <span className="sr-only">{open ? "Close menu" : "Open menu"}</span>
        </button>
      </div>

      {/*
       * The backdrop is a button rather than a div with onClick: tapping outside
       * to dismiss is an action, and an action needs to be reachable without a
       * pointer.
       */}
      {open ? (
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="fixed inset-0 z-30 bg-navy/40 lg:hidden"
        >
          <span className="sr-only">Close menu</span>
        </button>
      ) : null}

      <div
        id={navId}
        ref={panelRef}
        tabIndex={-1}
        hidden={!open}
        className={cn(
          "fixed inset-y-0 left-0 z-40 flex w-[17rem] max-w-[85vw] flex-col bg-surface shadow-xl outline-none lg:hidden",
        )}
      >
        <div className="flex items-center justify-between border-b border-line px-4 py-3">
          <Wordmark />
          <button
            type="button"
            onClick={() => {
              setOpen(false);
              toggleRef.current?.focus();
            }}
            className="inline-flex h-11 w-11 items-center justify-center rounded-[var(--radius-control)] text-ink-muted hover:bg-sunken hover:text-ink"
          >
            <X aria-hidden="true" className="h-5 w-5" />
            <span className="sr-only">Close menu</span>
          </button>
        </div>
        {navigation}
      </div>

      {/* Desktop: a permanent column. */}
      <div className="hidden w-[17rem] shrink-0 border-r border-line bg-surface lg:sticky lg:top-0 lg:flex lg:h-screen lg:flex-col">
        <div className="px-5 py-5">
          <Wordmark />
        </div>
        {navigation}
      </div>
    </>
  );
}
