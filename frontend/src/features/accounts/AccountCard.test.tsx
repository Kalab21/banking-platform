import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AccountCard } from "@/features/accounts/AccountCard";
import { TransactionRow } from "@/features/transactions/TransactionRow";
import { VirtualCard } from "@/features/cards/VirtualCard";
import type { Account, CreditCard, Transaction } from "@/types/api";

/**
 * The components a customer reads their money off.
 *
 * These assert on what is rendered and what is announced, not on class names: a
 * card that still shows the right balance after a restyle should not fail, and
 * one that leaks a full account number should fail however it is styled.
 *
 * All values are synthetic.
 */

function account(overrides: Partial<Account> = {}): Account {
  return {
    id: 1,
    accountNumber: "BA260917330682",
    userId: 42,
    accountType: "CHECKING",
    status: "ACTIVE",
    balance: 12_540.21,
    currency: "USD",
    interestRate: 0.001,
    overdraftLimit: 500,
    overdraftBalance: 0,
    availableBalance: 13_040.21,
    createdAt: "2026-01-15T10:00:00Z",
    ...overrides,
  };
}

function transaction(overrides: Partial<Transaction> = {}): Transaction {
  return {
    id: 1,
    transactionRef: "TX-0001",
    accountId: 1,
    type: "DEPOSIT",
    amount: 3_250,
    currency: "USD",
    balanceAfter: 12_540.21,
    description: "Direct deposit",
    relatedTransactionRef: null,
    status: "COMPLETED",
    createdAt: "2026-09-17T14:30:00Z",
    ...overrides,
  };
}

function card(overrides: Partial<CreditCard> = {}): CreditCard {
  return {
    id: 7,
    maskedCardNumber: "•••• •••• •••• 4812",
    last4: "4812",
    userId: 42,
    applicationId: null,
    cardType: "GOLD",
    creditLimit: 10_000,
    availableCredit: 7_500,
    currentBalance: 2_500,
    statementBalance: 2_400,
    minimumPaymentDue: 75,
    paymentDueDate: "2026-10-05",
    apr: 19.99,
    billingCycleDay: 5,
    status: "ACTIVE",
    currency: "USD",
    rewardsPoints: 1_240,
    linkedAccountId: null,
    createdAt: "2026-02-01T10:00:00Z",
    updatedAt: "2026-09-01T10:00:00Z",
    ...overrides,
  };
}

describe("AccountCard", () => {
  it("shows the balance, the available balance and the status", () => {
    render(<AccountCard account={account()} />);

    expect(screen.getByText("$12,540.21")).toBeInTheDocument();
    expect(screen.getByText(/\$13,040\.21 available/)).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
    expect(screen.getByText("Checking account")).toBeInTheDocument();
  });

  it("never renders the full account number", () => {
    render(<AccountCard account={account()} />);

    expect(screen.queryByText(/BA260917330682/)).not.toBeInTheDocument();
    expect(screen.getByText("••••0682")).toBeInTheDocument();
  });

  it("is a link to the account, not a clickable box", () => {
    // A div with an onClick cannot be tabbed to, opened in a new tab, or
    // announced as a destination.
    render(<AccountCard account={account()} />);

    expect(screen.getByRole("link")).toHaveAttribute("href", "/accounts/1");
  });

  it("marks a negative balance and names the overdraft in use", () => {
    render(
      <AccountCard
        account={account({ balance: -240.5, overdraftBalance: 240.5, status: "OVERDRAWN" })}
      />,
    );

    expect(screen.getByText("-$240.50")).toBeInTheDocument();
    expect(screen.getByText(/\$240\.50 overdraft used/)).toBeInTheDocument();
    expect(screen.getByText("Overdrawn")).toBeInTheDocument();
  });

  it("says nothing about an overdraft that is not being used", () => {
    render(<AccountCard account={account()} />);
    expect(screen.queryByText(/overdraft used/)).not.toBeInTheDocument();
  });
});

describe("TransactionRow", () => {
  it("signs a credit and a debit differently in text, not only in colour", () => {
    const { rerender } = render(
      <ul>
        <TransactionRow transaction={transaction()} />
      </ul>,
    );
    expect(screen.getByText("+$3,250.00")).toBeInTheDocument();

    rerender(
      <ul>
        <TransactionRow transaction={transaction({ type: "WITHDRAWAL", amount: 500 })} />
      </ul>,
    );
    expect(screen.getByText("−$500.00")).toBeInTheDocument();
  });

  it("falls back to the transaction type when there is no description", () => {
    render(
      <ul>
        <TransactionRow transaction={transaction({ description: null, type: "TRANSFER_OUT" })} />
      </ul>,
    );

    expect(screen.getByText("Transfer Out")).toBeInTheDocument();
  });

  it("masks the account it names", () => {
    render(
      <ul>
        <TransactionRow transaction={transaction()} accountNumber="BA260917330682" />
      </ul>,
    );

    expect(screen.getByText(/••••0682/)).toBeInTheDocument();
    expect(screen.queryByText(/BA260917330682/)).not.toBeInTheDocument();
  });

  it("labels the running balance so it is not read as a second amount", () => {
    render(
      <ul>
        <TransactionRow transaction={transaction()} showBalanceAfter />
      </ul>,
    );

    expect(screen.getByText("Balance after:")).toBeInTheDocument();
  });
});

describe("VirtualCard", () => {
  it("shows the masked number and the tier", () => {
    render(<VirtualCard card={card()} />);

    expect(screen.getByText("•••• •••• •••• 4812")).toBeInTheDocument();
    expect(screen.getByText("Gold")).toBeInTheDocument();
  });

  it("invents no field the API does not return", () => {
    // No expiry, no CVV, no cardholder name and no network logo. The card
    // record has none of them.
    const { container } = render(<VirtualCard card={card()} />);
    const text = container.textContent ?? "";

    expect(text).not.toMatch(/valid thru|expires|exp\b/i);
    expect(text).not.toMatch(/cvv|cvc/i);
    expect(text).not.toMatch(/visa|mastercard|amex/i);
    expect(text).not.toMatch(/\d{2}\/\d{2}/);
  });

  it("is decorative, because every figure on it is repeated as real text", () => {
    const { container } = render(<VirtualCard card={card()} />);
    expect(container.firstElementChild).toHaveAttribute("aria-hidden", "true");
  });
});
