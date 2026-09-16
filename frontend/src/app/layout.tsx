import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";

const inter = Inter({
  subsets: ["latin"],
  variable: "--font-inter",
  display: "swap",
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
