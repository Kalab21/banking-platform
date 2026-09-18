import "server-only";

import { apiFetch, apiFetchOptional } from "@/lib/api/client";
import type {
  Account,
  AmortizationScheduleRow,
  Application,
  AuthResponse,
  Beneficiary,
  CreditCard,
  CreditCardStatement,
  CreditCardTransaction,
  CreditScore,
  FraudAlert,
  KycDocument,
  Loan,
  LoanRepayment,
  Notification,
  PagedNotifications,
  Page,
  PayoffQuote,
  Payment,
  PayoffQuote as PayoffQuoteType,
  Transaction,
  TransferResult,
  TwoFactorSetup,
  UserProfile,
  UserStats,
} from "@/types/api";

/**
 * Typed wrappers over the gateway's routes.
 *
 * Each function maps to exactly one endpoint that exists in the backend today.
 * Paths were taken from the Spring controllers, not guessed.
 */

// ---------------------------------------------------------------------- auth

/**
 * Sign in.
 *
 * `totpCode` is only needed by accounts with two-factor enabled. Omitting it on
 * such an account returns `twoFactorRequired: true` and no token.
 */
export function login(
  username: string,
  password: string,
  totpCode?: string,
): Promise<AuthResponse> {
  return apiFetch<AuthResponse>("/api/auth/login", {
    method: "POST",
    body: { username, password, totpCode },
    anonymous: true,
  });
}

export interface RegisterPayload {
  username: string;
  email: string;
  password: string;
  firstName: string;
  middleName?: string;
  lastName: string;
  /** ISO date, `YYYY-MM-DD`. */
  dateOfBirth: string;
  /** Ten digits, not a formatted display string. */
  phone: string;
  streetAddress: string;
  addressLine2?: string;
  city: string;
  /** Two-letter USPS code. */
  state: string;
  postalCode: string;
  /**
   * Nine digits, sent once.
   *
   * The server checks the shape, keeps the last four digits and discards the
   * rest. Nothing on this side stores it: it is read from the form, passed
   * through this call, and gone.
   */
  ssn: string;
}

export function register(payload: RegisterPayload): Promise<AuthResponse> {
  return apiFetch<AuthResponse>("/api/auth/register", {
    method: "POST",
    body: payload,
    anonymous: true,
  });
}

// ---------------------------------------------------------------------- users

export function getUser(userId: number): Promise<UserProfile> {
  return apiFetch<UserProfile>(`/api/users/${userId}`);
}

export function getCreditScore(userId: number): Promise<CreditScore | null> {
  return apiFetchOptional<CreditScore | null>(`/api/users/${userId}/credit-score`, {}, null);
}

/** `POST /api/auth/2fa/setup?userId=` — starts enrolment and returns the provisioning URI. */
export function setupTwoFactor(userId: number): Promise<TwoFactorSetup> {
  return apiFetch<TwoFactorSetup>("/api/auth/2fa/setup", {
    method: "POST",
    query: { userId },
  });
}

/** `POST /api/auth/2fa/verify?userId=` — confirms the code and enables 2FA. */
export function verifyTwoFactor(userId: number, code: string): Promise<{ message: string }> {
  return apiFetch<{ message: string }>("/api/auth/2fa/verify", {
    method: "POST",
    query: { userId },
    body: { code },
  });
}

export function disableTwoFactor(userId: number, code: string): Promise<{ message: string }> {
  return apiFetch<{ message: string }>("/api/auth/2fa", {
    method: "DELETE",
    query: { userId },
    body: { code },
  });
}

// ------------------------------------------------------------------- accounts

export function getAccounts(userId: number): Promise<Account[]> {
  return apiFetchOptional<Account[]>(`/api/accounts/user/${userId}`, {}, []);
}

export function getAccount(accountId: number): Promise<Account> {
  return apiFetch<Account>(`/api/accounts/${accountId}`);
}

// --------------------------------------------------------------- transactions

export function getTransactions(
  accountId: number,
  page = 0,
  size = 20,
): Promise<Page<Transaction> | null> {
  return apiFetchOptional<Page<Transaction> | null>(
    `/api/transactions/account/${accountId}`,
    { query: { page, size } },
    null,
  );
}

export function deposit(
  accountId: number,
  amount: number,
  idempotencyKey: string,
  description?: string,
): Promise<Transaction> {
  return apiFetch<Transaction>("/api/transactions/deposit", {
    method: "POST",
    body: { accountId, amount, description },
    idempotencyKey,
  });
}

export function withdraw(
  accountId: number,
  amount: number,
  idempotencyKey: string,
  description?: string,
): Promise<Transaction> {
  return apiFetch<Transaction>("/api/transactions/withdraw", {
    method: "POST",
    body: { accountId, amount, description },
    idempotencyKey,
  });
}

export function transfer(
  fromAccountId: number,
  toAccountId: number,
  amount: number,
  idempotencyKey: string,
  description?: string,
): Promise<TransferResult> {
  return apiFetch<TransferResult>("/api/transactions/transfer", {
    method: "POST",
    body: { fromAccountId, toAccountId, amount, description },
    idempotencyKey,
  });
}

// -------------------------------------------------------------------- loans

export function getLoans(userId: number): Promise<Loan[]> {
  return apiFetchOptional<Loan[]>(`/api/loans/user/${userId}`, {}, []);
}

export function getLoan(loanId: number): Promise<Loan> {
  return apiFetch<Loan>(`/api/loans/${loanId}`);
}

export function getAmortizationSchedule(loanId: number): Promise<AmortizationScheduleRow[]> {
  return apiFetchOptional<AmortizationScheduleRow[]>(`/api/loans/${loanId}/schedule`, {}, []);
}

export function getPayoffQuote(loanId: number): Promise<PayoffQuoteType | null> {
  return apiFetchOptional<PayoffQuote | null>(`/api/loans/${loanId}/payoff-quote`, {}, null);
}

export function getLoanRepayments(loanId: number): Promise<LoanRepayment[]> {
  return apiFetchOptional<LoanRepayment[]>(`/api/loans/${loanId}/repayments`, {}, []);
}

export function repayLoan(
  loanId: number,
  amount: number,
  sourceAccountId?: number,
): Promise<LoanRepayment> {
  return apiFetch<LoanRepayment>(`/api/loans/${loanId}/repay`, {
    method: "POST",
    body: { amount, sourceAccountId },
  });
}

// -------------------------------------------------------------- credit cards

export function getCreditCards(userId: number): Promise<CreditCard[]> {
  return apiFetchOptional<CreditCard[]>(`/api/credit-cards/user/${userId}`, {}, []);
}

export function getCreditCard(cardId: number): Promise<CreditCard> {
  return apiFetch<CreditCard>(`/api/credit-cards/${cardId}`);
}

export function getCardTransactions(
  cardId: number,
  page = 0,
  size = 20,
): Promise<Page<CreditCardTransaction> | null> {
  return apiFetchOptional<Page<CreditCardTransaction> | null>(
    `/api/credit-cards/${cardId}/transactions`,
    { query: { page, size } },
    null,
  );
}

export function getCardStatements(cardId: number): Promise<CreditCardStatement[]> {
  return apiFetchOptional<CreditCardStatement[]>(`/api/credit-cards/${cardId}/statements`, {}, []);
}

// -------------------------------------------------------------- notifications

export function getNotifications(userId: number, page = 0, size = 20): Promise<PagedNotifications> {
  return apiFetchOptional<PagedNotifications>(
    `/api/notifications/user/${userId}`,
    { query: { page, size } },
    { notifications: [], unreadCount: 0, page: 0, size, totalElements: 0, totalPages: 0 },
  );
}

export function markNotificationRead(id: number): Promise<Notification> {
  // No userId: the service marks the caller's own notification, identified by
  // the session the gateway resolves. Sending one would suggest the client
  // chooses whose notification is read.
  return apiFetch<Notification>(`/api/notifications/${id}/read`, { method: "PUT" });
}

export function markAllNotificationsRead(userId: number): Promise<void> {
  return apiFetch<void>(`/api/notifications/user/${userId}/read-all`, { method: "PUT" });
}

// ---------------------------------------------------------------------- kyc

export function getKycDocuments(userId: number): Promise<KycDocument[]> {
  return apiFetchOptional<KycDocument[]>(`/api/users/${userId}/kyc/documents`, {}, []);
}

export function submitKycDocument(
  userId: number,
  documentType: string,
  documentRef: string,
): Promise<KycDocument> {
  return apiFetch<KycDocument>(`/api/users/${userId}/kyc/documents`, {
    method: "POST",
    body: { documentType, documentRef },
  });
}

/** Staff only — the backend enforces this with `@PreAuthorize`. */
export function reviewKycDocument(
  documentId: number,
  status: "APPROVED" | "REJECTED",
  reviewerId: number,
  rejectionReason?: string,
): Promise<KycDocument> {
  return apiFetch<KycDocument>(`/api/kyc/documents/${documentId}/review`, {
    method: "PUT",
    query: { reviewerId },
    body: { status, rejectionReason },
  });
}

// ------------------------------------------------------------------ payments

export function getBeneficiaries(userId: number): Promise<Beneficiary[]> {
  return apiFetchOptional<Beneficiary[]>(`/api/payments/beneficiaries/user/${userId}`, {}, []);
}

export function getPayments(accountId: number): Promise<Payment[]> {
  return apiFetchOptional<Payment[]>(`/api/payments/account/${accountId}`, {}, []);
}

export function getScheduledPayments(accountId: number): Promise<Payment[]> {
  return apiFetchOptional<Payment[]>(`/api/payments/account/${accountId}/scheduled`, {}, []);
}

export function cancelPayment(paymentId: number): Promise<Payment> {
  return apiFetch<Payment>(`/api/payments/${paymentId}/cancel`, { method: "PUT" });
}

// --------------------------------------------------------------- applications

export function getApplications(userId: number): Promise<Application[]> {
  return apiFetchOptional<Application[]>(`/api/applications/user/${userId}`, {}, []);
}

/** Staff view of the review queue. */
export function getApplicationsByStatus(status: string): Promise<Application[]> {
  return apiFetchOptional<Application[]>(`/api/applications/status/${status}`, {}, []);
}

// --------------------------------------------------------------------- fraud

/** Staff only. Returns the currently open alerts. */
export function getOpenFraudAlerts(): Promise<FraudAlert[]> {
  return apiFetchOptional<FraudAlert[]>("/api/fraud/alerts", {}, []);
}

// ---------------------------------------------------------------- statistics

export function getUserStats(userId: number): Promise<UserStats | null> {
  return apiFetchOptional<UserStats | null>(`/api/statistics/users/${userId}`, {}, null);
}
