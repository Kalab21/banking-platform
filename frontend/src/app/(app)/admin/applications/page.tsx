import type { Metadata } from "next";
import Link from "next/link";
import { requireStaffSession } from "@/lib/session";
import { getApplicationsByStatus } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  PageHeader,
  TableShell,
  Td,
  Th,
  statusTone,
} from "@/components/ui/primitives";
import { formatCurrency, formatDateTime, humanise } from "@/lib/format";

export const metadata: Metadata = { title: "Applications" };

const STATUSES = ["PENDING", "UNDER_REVIEW", "APPROVED", "REJECTED", "CANCELLED"] as const;

export default async function AdminApplicationsPage({
  searchParams,
}: {
  searchParams: Promise<{ status?: string }>;
}) {
  await requireStaffSession();
  const { status: rawStatus } = await searchParams;
  const status = STATUSES.includes(rawStatus as (typeof STATUSES)[number])
    ? (rawStatus as string)
    : "PENDING";

  let applications;
  try {
    applications = await getApplicationsByStatus(status);
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Applications" />
          <ErrorState message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  return (
    <>
      <PageHeader
        title="Applications"
        description="Product applications across the platform, filtered by status."
      />

      <nav aria-label="Filter by status" className="flex flex-wrap gap-2">
        {STATUSES.map((s) => (
          <Link
            key={s}
            href={`/admin/applications?status=${s}`}
            aria-current={s === status ? "page" : undefined}
            className={`rounded-md border px-3 py-1.5 text-sm transition-colors ${
              s === status
                ? "border-accent bg-accent-soft font-medium text-accent"
                : "border-line-strong bg-surface text-ink-muted hover:bg-sunken"
            }`}
          >
            {humanise(s)}
          </Link>
        ))}
      </nav>

      <Card>
        <CardHeader
          title={`${humanise(status)} applications`}
          description={`${applications.length} found`}
        />
        {applications.length === 0 ? (
          <EmptyState title={`No ${humanise(status).toLowerCase()} applications`} />
        ) : (
          <TableShell label="Applications">
            <thead>
              <tr>
                <Th>Applied</Th>
                <Th>Customer</Th>
                <Th>Product</Th>
                <Th align="right">Requested</Th>
                <Th align="right">Approved</Th>
                <Th align="right">Score</Th>
                <Th>Status</Th>
              </tr>
            </thead>
            <tbody>
              {applications.map((a) => (
                <tr key={a.id} className="hover:bg-sunken">
                  <Td>{formatDateTime(a.appliedAt ?? a.createdAt)}</Td>
                  <Td>#{a.userId}</Td>
                  <Td>
                    {humanise(a.applicationType)}
                    {a.termMonths ? (
                      <span className="block text-xs text-ink-subtle">{a.termMonths} months</span>
                    ) : null}
                  </Td>
                  <Td align="right">
                    {a.requestedAmount !== null
                      ? formatCurrency(a.requestedAmount, a.currency)
                      : "—"}
                  </Td>
                  <Td align="right">
                    {a.approvedAmount !== null ? formatCurrency(a.approvedAmount, a.currency) : "—"}
                  </Td>
                  <Td align="right">{a.creditScoreAtApply ?? "—"}</Td>
                  <Td>
                    <Badge tone={statusTone(a.status)}>{humanise(a.status)}</Badge>
                  </Td>
                </tr>
              ))}
            </tbody>
          </TableShell>
        )}
      </Card>
    </>
  );
}
