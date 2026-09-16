import type { ReactNode } from "react";

/** Centred, chrome-free shell for the signed-out pages. */
export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center px-4 py-12">
      <main id="main" className="w-full max-w-md">
        {children}
      </main>
      <p className="mt-8 max-w-md text-center text-xs text-ink-subtle">
        Demonstration banking console. Not a real bank — no real money or customer data.
      </p>
    </div>
  );
}
