import type { Metadata } from "next";
import { requireSession } from "@/lib/session";
import { getCreditScore, getKycDocuments, getUser } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  ErrorState,
  PageHeader,
  TableShell,
  Td,
  Th,
  statusTone,
} from "@/components/ui/primitives";
import { formatDate, formatDateTime, humanise } from "@/lib/format";
import { formatPhone } from "@/lib/phone";
import { maskedSsn } from "@/lib/ssn";
import { stateName } from "@/lib/us-states";
import { TwoFactorPanel } from "@/features/profile/TwoFactorPanel";
import { KycSubmitForm } from "@/features/kyc/KycSubmitForm";

export const metadata: Metadata = { title: "Profile & security" };

export default async function ProfilePage() {
  const session = await requireSession();

  let profile;
  let documents;
  let creditScore;
  try {
    [profile, documents, creditScore] = await Promise.all([
      getUser(session.userId),
      getKycDocuments(session.userId),
      getCreditScore(session.userId),
    ]);
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Profile & security" />
          <ErrorState message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  return (
    <>
      <PageHeader
        title="Profile & security"
        description="Your details, identity verification and second factor."
      />

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader title="Your details" />
          <CardBody>
            <dl className="grid gap-3 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-xs text-ink-subtle">Name</dt>
                <dd className="text-ink">
                  {[profile.firstName, profile.middleName, profile.lastName]
                    .filter(Boolean)
                    .join(" ")}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-ink-subtle">Date of birth</dt>
                <dd className="text-ink">
                  {profile.dateOfBirth ? formatDate(profile.dateOfBirth) : "—"}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-ink-subtle">Username</dt>
                <dd className="text-ink">{profile.username}</dd>
              </div>
              <div>
                <dt className="text-xs text-ink-subtle">Email</dt>
                <dd className="break-all text-ink">{profile.email}</dd>
              </div>
              <div>
                <dt className="text-xs text-ink-subtle">Phone</dt>
                <dd className="text-ink">
                  {profile.phone ? formatPhone(profile.phone) : "—"}
                </dd>
              </div>
              {/*
               * The address the customer gave at onboarding, read back from the
               * columns it was written to. This is the check on whether asking
               * for it was honest: a field collected by a form and not shown
               * again was decoration.
               */}
              <div className="sm:col-span-2">
                <dt className="text-xs text-ink-subtle">Home address</dt>
                <dd className="text-ink">
                  {profile.streetAddress ? (
                    <>
                      {profile.streetAddress}
                      {profile.addressLine2 ? `, ${profile.addressLine2}` : ""}
                      <br />
                      {[profile.city, stateName(profile.state)].filter(Boolean).join(", ")}{" "}
                      {profile.postalCode}
                    </>
                  ) : (
                    "—"
                  )}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-ink-subtle">Role</dt>
                <dd className="text-ink">{humanise(profile.role)}</dd>
              </div>
              <div>
                <dt className="text-xs text-ink-subtle">Customer since</dt>
                <dd className="text-ink">{formatDate(profile.createdAt)}</dd>
              </div>
              <div>
                <dt className="text-xs text-ink-subtle">Credit score</dt>
                <dd className="tabular text-ink">
                  {creditScore ? `${creditScore.score} (${creditScore.rating})` : "Not yet scored"}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-ink-subtle">KYC</dt>
                <dd>
                  <Badge tone={statusTone(profile.kycStatus)}>{humanise(profile.kycStatus)}</Badge>
                </dd>
              </div>
              {/*
               * Four digits, because four digits is all that was kept. The
               * status says the details were submitted; it does not say they
               * were verified, because nothing has verified them.
               */}
              <div>
                <dt className="text-xs text-ink-subtle">Social Security number</dt>
                <dd className="tabular text-ink">
                  {profile.ssnLast4 ? maskedSsn(profile.ssnLast4) : "—"}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-ink-subtle">Identity</dt>
                <dd className="text-ink">
                  {profile.identityStatus === "SUBMITTED"
                    ? "Submitted — verification pending"
                    : "Not submitted"}
                </dd>
              </div>
            </dl>
          </CardBody>
        </Card>

        <Card>
          <CardHeader
            title="Two-factor authentication"
            description="Time-based one-time passwords (TOTP)."
          />
          <TwoFactorPanel enabled={profile.twoFactorEnabled} />
        </Card>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader title="Submit a document" description="Identity verification for your account." />
          <CardBody>
            <KycSubmitForm />
          </CardBody>
        </Card>

        <Card>
          <CardHeader title="Submitted documents" />
          {documents.length === 0 ? (
            <EmptyState
              title="Nothing submitted yet"
              description="Documents you send for verification will be listed here."
            />
          ) : (
            <TableShell label="KYC documents">
              <thead>
                <tr>
                  <Th>Type</Th>
                  <Th>Reference</Th>
                  <Th>Status</Th>
                  <Th>Submitted</Th>
                </tr>
              </thead>
              <tbody>
                {documents.map((d) => (
                  <tr key={d.id} className="hover:bg-sunken">
                    <Td>{humanise(d.documentType)}</Td>
                    <Td className="font-mono text-xs text-ink-subtle">{d.documentRef}</Td>
                    <Td>
                      <Badge tone={statusTone(d.status)}>{humanise(d.status)}</Badge>
                      {d.rejectionReason ? (
                        <span className="mt-1 block text-xs text-critical">{d.rejectionReason}</span>
                      ) : null}
                    </Td>
                    <Td>{formatDateTime(d.createdAt)}</Td>
                  </tr>
                ))}
              </tbody>
            </TableShell>
          )}
        </Card>
      </div>
    </>
  );
}
