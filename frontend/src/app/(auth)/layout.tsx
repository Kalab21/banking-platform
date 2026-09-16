import type { ReactNode } from "react";
import { Wordmark } from "@/components/layout/Wordmark";

/**
 * Signed-out shell.
 *
 * Two columns on desktop: what the system is on the left, the form on the right.
 * The left column collapses away on small screens so the form is the first thing
 * a phone user sees. Claims here describe the architecture only — nothing about
 * regulatory status, because this is a demonstration system.
 */

const HIGHLIGHTS: { title: string; detail: string }[] = [
  {
    title: "13 Spring Boot microservices",
    detail: "Accounts, payments, lending, cards, fraud and notifications, each owning its data.",
  },
  {
    title: "Event-driven over Kafka",
    detail: "Transactions publish once; statistics, alerts and fraud scoring consume independently.",
  },
  {
    title: "Session never leaves the server",
    detail: "The JWT lives in an httpOnly cookie; page JavaScript cannot read it.",
  },
];

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[1.05fr_1fr]">
      {/* Identity and context — desktop only */}
      <aside className="hidden flex-col justify-between bg-ink px-12 py-12 lg:flex">
        <div className="flex items-center gap-2">
          <span
            aria-hidden="true"
            className="flex h-8 w-8 items-center justify-center rounded bg-accent text-sm font-bold text-white"
          >
            N
          </span>
          <span className="text-base font-semibold tracking-tight text-white">Northbank</span>
        </div>

        <div className="max-w-md">
          <p className="text-xs font-medium uppercase tracking-widest text-accent">
            Retail banking platform
          </p>
          <h2 className="mt-3 text-3xl font-semibold leading-tight tracking-tight text-white">
            A distributed banking core, and the console that drives it.
          </h2>

          <ul className="mt-8 space-y-5">
            {HIGHLIGHTS.map((item) => (
              <li key={item.title} className="border-l-2 border-accent pl-4">
                <p className="text-sm font-medium text-white">{item.title}</p>
                <p className="mt-0.5 text-sm leading-relaxed text-white/60">{item.detail}</p>
              </li>
            ))}
          </ul>
        </div>

        <p className="max-w-md text-xs leading-relaxed text-white/45">
          Portfolio demonstration system. Not a real bank: it holds no real money, no real customer
          data, and makes no regulatory or compliance claims.
        </p>
      </aside>

      {/* Form column */}
      <div className="flex min-h-screen flex-col items-center justify-center px-5 py-12 sm:px-8">
        <main id="main" className="w-full max-w-sm">
          <div className="mb-8 flex justify-center lg:hidden">
            <Wordmark />
          </div>
          {children}
        </main>

        <p className="mt-10 max-w-sm text-center text-xs leading-relaxed text-ink-subtle lg:hidden">
          Portfolio demonstration system. Not a real bank — no real money or customer data.
        </p>
      </div>
    </div>
  );
}
