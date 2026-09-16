import Link from "next/link";

export default function NotFound() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center px-4 text-center">
      <p className="text-sm font-medium text-accent">404</p>
      <h1 className="mt-2 text-xl font-semibold tracking-tight text-ink">Page not found</h1>
      <p className="mt-1 max-w-sm text-sm text-ink-muted">
        The page you were looking for does not exist, or you do not have access to it.
      </p>
      <Link
        href="/dashboard"
        className="mt-6 rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent-hover"
      >
        Back to dashboard
      </Link>
    </div>
  );
}
