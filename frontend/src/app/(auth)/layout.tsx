import { ArrowDownLeft, ArrowUpRight, Eye, Lock, ShieldCheck } from "lucide-react";
import type { ReactNode } from "react";
import { Wordmark } from "@/components/layout/Wordmark";

/**
 * Signed-out shell.
 *
 * Two columns on desktop: the product on the left, the form on the right. The
 * left column collapses away on small screens so a phone user sees the form
 * first rather than scrolling past marketing to reach it.
 *
 * The copy here is customer-facing and says what the product does. The
 * architecture behind it — the services, the event bus, where the session lives
 * — is in the README, which is where someone assessing the engineering will
 * look. A person signing in to check a balance is not that person.
 */

const VALUE_POINTS: { icon: typeof ShieldCheck; title: string; detail: string }[] = [
  {
    icon: Lock,
    title: "Secure account access",
    detail: "Sign-in is verified server-side, with optional two-factor authentication.",
  },
  {
    icon: ShieldCheck,
    title: "Protected money movement",
    detail: "Every transfer is authorised against the account it draws on.",
  },
  {
    icon: Eye,
    title: "Clear account visibility",
    detail: "Balances, payments and lending in one place, updated as they settle.",
  },
];

/**
 * Sample product imagery for the brand panel.
 *
 * Illustrative figures, not anyone's account. Kept obviously round and generic
 * so it reads as a picture of the product rather than as somebody's data.
 */
function ProductPreview() {
  return (
    <div aria-hidden="true" className="select-none">
      <div className="w-full max-w-sm rounded-[var(--radius-card)] bg-white/[0.07] p-5 ring-1 ring-inset ring-white/10 backdrop-blur-sm">
        <p className="text-xs font-medium uppercase tracking-wider text-white/50">
          Available balance
        </p>
        <p className="tabular mt-1 text-3xl font-semibold tracking-tight text-white">$24,850.75</p>
        <p className="mt-1 text-sm text-white/55">Everyday Checking ···· 4821</p>

        <div className="mt-5 space-y-3 border-t border-white/10 pt-4">
          <div className="flex items-center gap-3">
            <span className="flex h-8 w-8 items-center justify-center rounded-full bg-positive/20 text-positive">
              <ArrowDownLeft className="h-4 w-4" />
            </span>
            <span className="flex-1 text-sm text-white/75">Direct deposit</span>
            <span className="tabular text-sm font-medium text-white">+$3,250.00</span>
          </div>
          <div className="flex items-center gap-3">
            <span className="flex h-8 w-8 items-center justify-center rounded-full bg-white/10 text-white/70">
              <ArrowUpRight className="h-4 w-4" />
            </span>
            <span className="flex-1 text-sm text-white/75">Utilities</span>
            <span className="tabular text-sm font-medium text-white/80">−$126.40</span>
          </div>
        </div>
      </div>
      <p className="mt-3 max-w-sm text-xs text-white/35">Illustrative figures.</p>
    </div>
  );
}

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[1fr_1fr] xl:grid-cols-[1.05fr_1fr]">
      {/* Brand and product context — desktop only. */}
      <aside className="relative hidden flex-col justify-between overflow-hidden bg-navy px-12 py-12 lg:sticky lg:top-0 lg:flex lg:h-screen xl:px-16">
        {/* A single soft wash, so the panel is not a flat rectangle. */}
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -right-24 -top-24 h-96 w-96 rounded-full bg-primary/20 blur-3xl"
        />

        <div className="relative">
          <Wordmark tone="inverse" size="md" />
        </div>

        <div className="relative max-w-lg">
          <h2 className="text-[2.125rem] font-semibold leading-[1.15] tracking-tight text-white">
            Bank securely.
            <br />
            Move money confidently.
          </h2>
          <p className="mt-4 max-w-md text-[0.9375rem] leading-relaxed text-white/60">
            Secure access to accounts, payments and lending in one place.
          </p>

          <ul className="mt-9 space-y-5">
            {VALUE_POINTS.map(({ icon: Icon, title, detail }) => (
              <li key={title} className="flex gap-3.5">
                <span
                  aria-hidden="true"
                  className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-[10px] bg-white/10 text-white"
                >
                  <Icon className="h-[18px] w-[18px]" />
                </span>
                <div>
                  <p className="text-sm font-medium text-white">{title}</p>
                  <p className="mt-0.5 text-sm leading-relaxed text-white/55">{detail}</p>
                </div>
              </li>
            ))}
          </ul>

          <div className="mt-10 hidden xl:block">
            <ProductPreview />
          </div>
        </div>

        <p className="relative max-w-md text-xs leading-relaxed text-white/40">
          Portfolio demonstration. No real money or customer data, and no regulatory or
          compliance claims.
        </p>
      </aside>

      {/* Form column. */}
      <div className="flex min-h-screen flex-col px-5 py-10 sm:px-8 lg:px-12">
        <div className="flex justify-center lg:hidden">
          <Wordmark size="md" />
        </div>

        <main
          id="main"
          className="mx-auto flex w-full max-w-[27rem] flex-1 flex-col justify-center py-8"
        >
          <div className="rounded-[var(--radius-card)] border border-line bg-surface p-6 shadow-[0_1px_2px_rgba(16,24,40,0.04),0_8px_24px_-12px_rgba(16,24,40,0.10)] sm:p-8">
            {children}
          </div>
        </main>

        <p className="mx-auto max-w-[26rem] text-center text-xs leading-relaxed text-ink-subtle lg:hidden">
          Portfolio demonstration. No real money or customer data.
        </p>
      </div>
    </div>
  );
}
