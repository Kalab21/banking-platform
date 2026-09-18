import type { ReactNode } from "react";
import { requireSession } from "@/lib/session";
import { getCurrentUserOrNull } from "@/lib/current-user";
import { Sidebar } from "@/components/layout/Sidebar";

/**
 * Shell for every authenticated page.
 *
 * `requireSession` runs on the server before anything renders, so an expired or
 * missing cookie redirects to login rather than flashing an empty dashboard.
 *
 * The profile is read here for the customer's name, and read again by the pages
 * that need the rest of it. That is one request, not two: `getCurrentUser` is
 * memoised per request, and a failure degrades to the username rather than
 * replacing the page with an error — the shell wraps every route, so it must
 * not be the thing that breaks one.
 */
export default async function AppLayout({ children }: { children: ReactNode }) {
  const session = await requireSession();
  const profile = await getCurrentUserOrNull(session.userId);

  return (
    <div className="flex min-h-screen flex-col lg:flex-row">
      <Sidebar
        role={session.role}
        username={session.username}
        firstName={profile?.firstName}
        lastName={profile?.lastName}
      />

      <div className="flex min-w-0 flex-1 flex-col">
        <main id="main" className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8 lg:py-8">
          <div className="mx-auto max-w-6xl space-y-6">{children}</div>
        </main>

        <footer className="px-4 pb-6 sm:px-6 lg:px-8">
          <p className="mx-auto max-w-6xl text-xs text-ink-subtle">
            Northbank is a portfolio demonstration. No real money and no real customer data.
          </p>
        </footer>
      </div>
    </div>
  );
}
