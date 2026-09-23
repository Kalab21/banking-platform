/**
 * Types mirroring the backend response DTOs exactly.
 *
 * Every shape here was read off the Java DTO it corresponds to — nothing is
 * invented. Monetary values arrive as JSON numbers (Jackson serialises
 * BigDecimal that way), and timestamps as ISO-8601 strings.
 */

// ---------------------------------------------------------------- auth & user

export type Role = "CUSTOMER" | "EMPLOYEE" | "ADMIN";

export type KycStatus = "NOT_STARTED" | "PENDING" | "IN_REVIEW" | "VERIFIED" | "REJECTED";

/**
 * `POST /api/auth/login` and `/api/auth/register`.
 *
 * Two shapes: on success `token` is present; when the account has 2FA enabled
 * and no valid code was supplied, `twoFactorRequired` is true and `token` is
 * null — the caller must retry with a code.
 */
export interface AuthResponse {
  token: string | null;
  type: string;
  userId: number;
  username: string;
  role: Role | null;
  expiresIn: number;
  twoFactorRequired: boolean;
}

/** Claims carried by the gateway-issued JWT. */
export interface JwtClaims {
  sub: string;
  userId: number;
  roles: string[];
  iat: number;
  exp: number;
}

/** `GET /api/users/{id}`. */
export interface UserProfile {
  id: number;
  username: string;
  email: string;
  firstName: string;
  middleName: string | null;
  lastName: string;
  phone: string | null;

  /*
   * Captured during onboarding. Null on accounts created before onboarding
   * existed, which is why these are nullable rather than required.
   */
  dateOfBirth: string | null;
  streetAddress: string | null;
  addressLine2: string | null;
  city: string | null;
  state: string | null;
  postalCode: string | null;

  /**
   * The last four digits of the Social Security number on file. The full number
   * is not stored by the backend, so there is no wider field to ask for.
   */
  ssnLast4: string | null;
  /**
   * `SUBMITTED` once identity details are given. There is no verification
   * provider behind this system, so this never reads "verified".
   */
  identityStatus: string | null;
  role: Role;
  enabled: boolean;
  creditScore: number;
  kycStatus: KycStatus;
  twoFactorEnabled: boolean;
  createdAt: string;
}

/** `POST /api/auth/2fa/setup`. */
export interface TwoFactorSetup {
  secret: string;
  otpauthUri: string;
  message: string;
}

/** `GET /api/users/{userId}/credit-score`. */
export interface CreditScore {
  userId: number;
  score: number;
  rating: string;
  updatedAt: string;
}

// -------------------------------------------------------------------- accounts

export type AccountType = "CHECKING" | "SAVINGS" | "BUSINESS";
export type AccountStatus = "ACTIVE" | "FROZEN" | "CLOSED" | "OVERDRAWN";

/** `GET /api/accounts/{id}`. */
export interface Account {
  id: number;
  accountNumber: string;
  userId: number;
  accountType: AccountType;
  status: AccountStatus;
  balance: number;
  currency: string;
  interestRate: number;
  overdraftLimit: number;
  overdraftBalance: number;
  availableBalance: number;
  createdAt: string;
}

// ---------------------------------------------------------------- transactions

export type TransactionType =
  | "DEPOSIT"
  | "WITHDRAWAL"
  | "TRANSFER_IN"
  | "TRANSFER_OUT"
  | "PAYMENT"
  | "FEE";

/** `GET /api/transactions/{ref}` and the page content of `/api/transactions/account/{id}`. */
export interface Transaction {
  id: number;
  transactionRef: string;
  accountId: number;
  type: TransactionType;
  amount: number;
  currency: string;
  balanceAfter: number;
  description: string | null;
  relatedTransactionRef: string | null;
  status: string;
  createdAt: string;
}

/** `POST /api/transactions/transfer` returns both legs. */
export interface TransferResult {
  debit: Transaction;
  credit: Transaction;
}

/** Spring Data `Page<T>` as serialised by Jackson. */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
  numberOfElements: number;
  empty: boolean;
}

// ------------------------------------------------------------------------ loans

export type LoanType = "PERSONAL_LOAN" | "AUTO_LOAN" | "MORTGAGE";
export type LoanStatus = "PENDING" | "ACTIVE" | "PAID_OFF" | "DEFAULTED" | "CLOSED";
export type ScheduleStatus = "PENDING" | "PAID" | "MISSED" | "PARTIAL";

/** `GET /api/loans/{loanId}`. */
export interface Loan {
  id: number;
  userId: number;
  applicationId: number | null;
  loanType: LoanType;
  principal: number;
  interestRate: number;
  termMonths: number;
  monthlyPayment: number;
  totalInterest: number;
  remainingBalance: number;
  disbursementAccountId: number | null;
  disbursedAt: string | null;
  nextPaymentDate: string | null;
  paymentsMade: number;
  status: LoanStatus;
  currency: string;
  createdAt: string;
  updatedAt: string;
}

/** `GET /api/loans/{loanId}/schedule`. */
export interface AmortizationScheduleRow {
  id: number;
  loanId: number;
  paymentNumber: number;
  dueDate: string;
  scheduledPayment: number;
  principalPortion: number;
  interestPortion: number;
  remainingBalance: number;
  status: ScheduleStatus;
  paidAt: string | null;
}

/** `GET /api/loans/{loanId}/payoff-quote`. */
export interface PayoffQuote {
  loanId: number;
  remainingBalance: number;
  accruedInterest: number;
  totalPayoffAmount: number;
  quoteDate: string;
  paymentsRemaining: number;
}

/** `GET /api/loans/{loanId}/repayments`. */
export interface LoanRepayment {
  id: number;
  loanId: number;
  paymentRef: string;
  amount: number;
  principalPaid: number;
  interestPaid: number;
  sourceAccountId: number | null;
  paymentNumber: number | null;
  isEarlyPayoff: boolean;
  createdAt: string;
}

// ----------------------------------------------------------------- credit cards

export type CardType = "STANDARD" | "GOLD" | "PLATINUM";
// The backend's vocabulary, exactly. These drifted apart: the console
// listed BLOCKED and EXPIRED, neither of which the service could ever
// return, and did not list the frozen states it actually did return.
export type CardStatus =
  | "ACTIVE"
  | "CUSTOMER_FROZEN"
  | "SYSTEM_BLOCKED"
  | "DEFAULTED"
  | "CLOSED";

/**
 * `GET /api/credit-cards/{cardId}`.
 *
 * The full card number never crosses the API boundary: the service sends only a
 * display mask and the last four digits.
 */
export interface CreditCard {
  id: number;
  /** Display form, e.g. `•••• •••• •••• 1234`. */
  maskedCardNumber: string;
  /** Last four digits, or null when the stored value was unusable. */
  last4: string | null;
  userId: number;
  applicationId: number | null;
  cardType: CardType;
  creditLimit: number;
  availableCredit: number;
  currentBalance: number;
  statementBalance: number;
  minimumPaymentDue: number;
  paymentDueDate: string | null;
  apr: number;
  billingCycleDay: number;
  status: CardStatus;
  currency: string;
  rewardsPoints: number;
  linkedAccountId: number | null;
  createdAt: string;
  updatedAt: string;
}

/** `GET /api/credit-cards/{cardId}/transactions` page content. */
export interface CreditCardTransaction {
  id: number;
  creditCardId: number;
  transactionRef: string;
  type: string;
  amount: number;
  description: string | null;
  merchantName: string | null;
  merchantCategory: string | null;
  status: string;
  createdAt: string;
}

/** `GET /api/credit-cards/{cardId}/statements`. */
export interface CreditCardStatement {
  id: number;
  creditCardId: number;
  statementDate: string;
  openingBalance: number;
  closingBalance: number;
  totalPurchases: number;
  totalPayments: number;
  interestCharged: number;
  feesCharged: number;
  rewardsEarned: number;
  minimumPayment: number;
  paymentDueDate: string;
  paidInFull: boolean;
  createdAt: string;
}

// ---------------------------------------------------------------- notifications

/** `GET /api/notifications/user/{userId}`. */
export interface Notification {
  id: number;
  userId: number;
  type: string;
  title: string;
  message: string;
  isRead: boolean;
  referenceId: string | null;
  referenceType: string | null;
  createdAt: string;
}

export interface PagedNotifications {
  notifications: Notification[];
  unreadCount: number;
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// -------------------------------------------------------------------------- kyc

export type DocumentStatus = "PENDING" | "APPROVED" | "REJECTED";

/** `GET /api/users/{userId}/kyc/documents`. */
export interface KycDocument {
  id: number;
  userId: number;
  documentType: string;
  documentRef: string;
  status: DocumentStatus;
  rejectionReason: string | null;
  reviewedBy: number | null;
  reviewedAt: string | null;
  createdAt: string;
}

// --------------------------------------------------------------------- payments

/** `GET /api/payments/beneficiaries/user/{userId}`. */
export interface Beneficiary {
  id: number;
  userId: number;
  name: string;
  nickname: string | null;
  accountNumber: string;
  bankName: string | null;
  routingNumber: string | null;
  swiftCode: string | null;
  iban: string | null;
  beneficiaryType: string;
  currency: string;
  verified: boolean;
  createdAt: string;
}

/** `GET /api/payments/account/{accountId}`. */
export interface Payment {
  id: number;
  paymentRef: string;
  payerAccountId: number;
  beneficiaryId: number | null;
  payeeAccountId: number | null;
  payeeExternalRef: string | null;
  paymentType: string;
  amount: number;
  currency: string;
  status: string;
  description: string | null;
  recurring: boolean;
  recurrencePattern: string | null;
  nextExecutionDate: string | null;
  endDate: string | null;
  scheduledAt: string | null;
  processedAt: string | null;
  failureReason: string | null;
  createdAt: string;
}

// ----------------------------------------------------------------------- fraud

export type AlertStatus = "OPEN" | "REVIEWED" | "DISMISSED" | "CONFIRMED";

/** `GET /api/fraud/alerts`. */
export interface FraudAlert {
  id: number;
  accountId: number;
  userId: number;
  alertType: string;
  riskScore: number;
  description: string;
  eventRef: string | null;
  eventType: string | null;
  amount: number | null;
  status: AlertStatus;
  createdAt: string;
}

// ------------------------------------------------------------------ statistics

/** `GET /api/statistics/users/{userId}`. */
export interface UserStats {
  userId: number;
  totalTransactions: number;
  totalAmountIn: number;
  totalAmountOut: number;
  totalPayments: number;
  totalPaymentVolume: number;
  totalAccounts: number;
  activeLoans: number;
  totalLoanAmount: number;
  ccTransactions: number;
  ccSpend: number;
  lastUpdated: string;
}

// ---------------------------------------------------------------- applications

/** `GET /api/applications/user/{userId}`. */
export interface Application {
  id: number;
  userId: number;
  applicationType: string;
  status: string;
  requestedAmount: number | null;
  approvedAmount: number | null;
  currency: string;
  termMonths: number | null;
  purpose: string | null;
  creditScoreAtApply: number | null;
  reviewerNotes: string | null;
  productId: number | null;
  appliedAt: string | null;
  reviewedAt: string | null;
  createdAt: string;
}

// ---------------------------------------------------------------------- errors

/** The shape produced by every service's `@RestControllerAdvice`. */
export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
}
