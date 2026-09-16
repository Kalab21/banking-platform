import type { Metadata } from "next";
import { requireStaffSession } from "@/lib/session";
import { getOpenFraudAlerts } from "@/lib/api/banking";
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

export const metadata: Metadata = { title: "Fraud alerts" };

/** Risk score bands, matching how the rules engine scores an event. */
function riskTone(score: number): "critical" | "caution" | "neutral" {
  if (score >= 70) return "critical";
  if (score >= 40) return "caution";
  return "neutral";
}

export default async function AdminFraudPage() {
  await requireStaffSession();

  let alerts;
  try {
    alerts = await getOpenFraudAlerts();
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Fraud alerts" />
          <ErrorState message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  return (
    <>
      <PageHeader
        title="Fraud alerts"
        description="Open alerts raised by the rules engine from platform events."
      />

      <Card>
        <CardHeader title="Open alerts" description={`${alerts.length} awaiting review`} />
        {alerts.length === 0 ? (
          <EmptyState
            title="No open alerts"
            description="Alerts appear here when velocity or amount rules trip."
          />
        ) : (
          <TableShell label="Open fraud alerts">
            <thead>
              <tr>
                <Th>Raised</Th>
                <Th>Type</Th>
                <Th align="right">Risk</Th>
                <Th>Account</Th>
                <Th>Description</Th>
                <Th align="right">Amount</Th>
                <Th>Status</Th>
              </tr>
            </thead>
            <tbody>
              {alerts.map((a) => (
                <tr key={a.id} className="hover:bg-sunken">
                  <Td>{formatDateTime(a.createdAt)}</Td>
                  <Td>{humanise(a.alertType)}</Td>
                  <Td align="right">
                    <Badge tone={riskTone(a.riskScore)}>{a.riskScore}</Badge>
                  </Td>
                  <Td>#{a.accountId}</Td>
                  <Td>{a.description}</Td>
                  <Td align="right">{a.amount !== null ? formatCurrency(a.amount) : "—"}</Td>
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
