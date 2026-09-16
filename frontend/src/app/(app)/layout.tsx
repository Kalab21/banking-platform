import type { ReactNode } from "react";
import { requireSession } from "@/lib/session";
import { Sidebar } from "@/components/layout/Sidebar";
import { SignOutButton } from "@/components/layout/SignOutButton";

/**
 * Shell for every authenticated page.
 *
 * `requireSession` runs on the server before anything renders, so an expired or
 * missing cookie redirects to login rather than flashing an empty dashboard.
 */
export default async function AppLayout({ children }: { children: ReactNode }) {
  const session = await requireSession();

  return (
    <div className="flex min-h-screen flex-col lg:flex-row">
      <Sidebar role={session.role} username={session.username} />

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="hidden items-center justify-end gap-4 border-b border-line bg-surface px-6 py-3 lg:flex">
          <span className="text-sm text-ink-muted">
            Demo environment — simulated data only
          </span>
          <SignOutButton />
        </header>

        <main id="main" className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">
          <div className="mx-auto max-w-6xl space-y-6">{children}</div>
        </main>

        <div className="border-t border-line bg-surface px-4 py-3 lg:hidden">
          <SignOutButton />
        </div>
      </div>
    </div>
  );
}
