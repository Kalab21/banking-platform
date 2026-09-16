import type { Metadata } from "next";
import localFont from "next/font/local";
import "./globals.css";

/**
 * Inter, self-hosted.
 *
 * Deliberately not `next/font/google`: that fetches the font from Google at
 * build time, so an image build fails behind a proxy or on an air-gapped
 * machine. Shipping the latin variable subset (48KB) keeps the build hermetic
 * and removes a runtime request to a third party.
 *
 * Inter is licensed under the SIL Open Font License 1.1 — see fonts/OFL.txt.
 */
const inter = localFont({
  src: "./fonts/Inter-Variable-latin.woff2",
  weight: "100 900",
  style: "normal",
  variable: "--font-inter",
  display: "swap",
  fallback: ["system-ui", "-apple-system", "Segoe UI", "sans-serif"],
});

export const metadata: Metadata = {
  title: {
    default: "Northbank — Banking Platform",
    template: "%s · Northbank",
  },
  description:
    "Demonstration retail banking console for the banking-platform microservices project. Not a real bank.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={inter.variable}>
      <body className="font-sans antialiased">
        <a
          href="#main"
          className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded focus:bg-surface focus:px-4 focus:py-2 focus:text-sm focus:font-medium focus:text-accent focus:shadow"
        >
          Skip to main content
        </a>
        {children}
      </body>
    </html>
  );
}
