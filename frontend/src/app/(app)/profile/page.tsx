import type { Metadata } from "next";
import { requireSession } from "@/lib/session";
import { getCurrentUser } from "@/lib/current-user";
import { getCreditScore, getKycDocuments } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
  CardBody,
  CardHeader,
  Detail,
  DetailList,
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
      getCurrentUser(session.userId),
      getKycDocuments(session.userId),
      getCreditScore(session.userId),
    ]);
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Profile & security" />
          <ErrorState title="We could not load your profile" message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  const fullName = [profile.firstName, profile.middleName, profile.lastName]
    .filter(Boolean)
    .join(" ");
  const cityLine = [profile.city, stateName(profile.state)].filter(Boolean).join(", ");

  return (
    <>
      <PageHeader
        title="Profile & security"
        description="The details on your account, and the controls that protect it."
      />

      <div className="grid gap-6 lg:grid-cols-2">
        {/* ------------------------------------------------------- personal */}
        <Card>
          <CardHeader title="Personal information" />
          <CardBody>
            <DetailList>
              <Detail label="Name">{fullName}</Detail>
              <Detail label="Date of birth">
                {profile.dateOfBirth ? formatDate(profile.dateOfBirth) : "Not on file"}
              </Detail>
              <Detail label="Username">{profile.username}</Detail>
              <Detail label="Customer since">{formatDate(profile.createdAt)}</Detail>
            </DetailList>
          </CardBody>
        </Card>

        {/* -------------------------------------------------------- contact */}
        <Card>
          <CardHeader title="Contact" />
          <CardBody>
            <DetailList>
              <Detail label="Email">
                <span className="break-all">{profile.email}</span>
              </Detail>
              <Detail label="Phone">
                {profile.phone ? formatPhone(profile.phone) : "Not on file"}
              </Detail>
            </DetailList>
          </CardBody>
        </Card>

        {/* -------------------------------------------------------- address */}
        <Card>
          <CardHeader title="Home address" />
          <CardBody>
            {profile.streetAddress ? (
              <address className="text-sm not-italic leading-relaxed text-ink">
                {profile.streetAddress}
                {profile.addressLine2 ? (
                  <>
                    <br />
                    {profile.addressLine2}
                  </>
                ) : null}
                <br />
                {cityLine} {profile.postalCode}
              </address>
            ) : (
              <p className="text-sm text-ink-muted">
                No address on file. Accounts opened before onboarding existed do not have one.
              </p>
            )}
          </CardBody>
        </Card>

        {/* ------------------------------------------------------- identity */}
        <Card>
          <CardHeader title="Identity" />
          <CardBody>
            <DetailList>
              {/*
               * Four digits, because four digits is all the server keeps. The
               * status says the details were submitted and nothing more: there
               * is no verification provider behind this system, and passing a
               * format check is not verification.
               */}
              <Detail label="Social Security number">
                <span className="tabular">
                  {profile.ssnLast4 ? maskedSsn(profile.ssnLast4) : "Not on file"}
                </span>
              </Detail>
              <Detail label="Identity status">
                {profile.identityStatus === "SUBMITTED"
                  ? "Submitted — verification pending"
                  : "Not submitted"}
              </Detail>
              <Detail label="Know-your-customer">
                <Badge tone={statusTone(profile.kycStatus)}>{humanise(profile.kycStatus)}</Badge>
              </Detail>
              <Detail label="Credit score">
                <span className="tabular">
                  {creditScore ? `${creditScore.score} · ${creditScore.rating}` : "Not yet scored"}
                </span>
              </Detail>
            </DetailList>
          </CardBody>
        </Card>
      </div>

      {/* -------------------------------------------------------- security */}
      <Card>
        <CardHeader
          title="Two-factor authentication"
          description="A time-based one-time password from an authenticator app, checked at sign-in."
        />
        <TwoFactorPanel enabled={profile.twoFactorEnabled} />
      </Card>

      {/* ------------------------------------------------------- documents */}
      <div className="grid gap-6 lg:grid-cols-2">
        <Card className="h-fit">
          <CardHeader
            title="Submit a document"
            description="Identity documents are reviewed by staff before a status changes."
          />
          <CardBody>
            <KycSubmitForm />
          </CardBody>
        </Card>

        <Card>
          <CardHeader title="Submitted documents" />
          {documents.length === 0 ? (
            <EmptyState
              title="Nothing submitted yet"
              description="Documents you send for verification will be listed here with their review status."
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
                        <span className="mt-1 block text-xs text-critical">
                          {d.rejectionReason}
                        </span>
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
