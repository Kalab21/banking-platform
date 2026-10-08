# Product Experience

Four workflows from the Northbank console, captured from the running application with
synthetic data. The README shows the customer dashboard and the staff review workbench.

## Move money: review before confirming

![The review step naming the amount and both accounts by their last four digits, above one confirm button](screenshots/23-move-money-review.png)

The review step names the amount and both accounts before anything moves. The console
mints one idempotency key here and reuses it on every retry, so a repeated submit
executes the transfer once.

## Guided credit application

![The last of three steps, repeating every answer with a way to change it](screenshots/27-credit-application.png)

Three steps, then a summary of every answer with a way to change it. Submitting hands the
application to a deterministic, versioned underwriting policy.

## Application detail and stored offer

![An application's own page with its history from stored timestamps, and the offer's terms above Accept and Decline](screenshots/33-application-detail.png)

The application's history is built from stored timestamps, and the offer's terms are
stored once. Only the applicant can accept or decline, and accepts exactly those terms.

## Loan payment and servicing

![A loan offering the rest of a part-paid instalment, another amount, or payoff at today's figure](screenshots/34-loan-payment.png)

A provisioned loan can be paid by instalment, by any amount, or paid off at today's
figure. Every payment carries its own idempotency key.

## The credit journey

```text
Explore / Apply → Underwrite → Review → Offer → Accept → Provision → Service
```

- **Underwriting** is a deterministic, versioned policy: score, debt-to-income,
  loan-to-value, amount and term, and identity check. Each decision is stored as an
  immutable record with its reason codes.
- **Referred applications** go to staff review, where a reviewer sees the policy's reason
  code and approves or rejects; staff cannot decide about themselves.
- **An offer's terms are stored once.** Staff and admins cannot act on the customer's
  behalf.
- **Provisioning happens once.** The card or loan service creates the product from the
  accepted terms and confirms its real ID; a redelivered event creates nothing new.
- **Servicing is in the console**: receive a loan, pay an instalment or any amount, pay
  the loan off, and pay a card.
