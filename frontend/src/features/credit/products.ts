import type { CreditProductType } from "@/types/api";

/**
 * The four credit products Northbank offers, and what each asks the customer.
 *
 * `asks` is the whole of what a customer may state. The rate, the term the bank
 * settles on, the limit and the tier are decided by underwriting and appear on
 * the offer — a form that collected them would be collecting an answer the
 * customer does not get to give.
 */
export interface CreditProduct {
  type: CreditProductType;
  name: string;
  summary: string;
  /** Terms the product offers, where the customer picks one. */
  terms?: number[];
  asks: {
    amount: boolean;
    term: boolean;
    asset: boolean;
    downPayment: boolean;
    purpose: boolean;
  };
}

export const CREDIT_PRODUCTS: CreditProduct[] = [
  {
    type: "CREDIT_CARD",
    name: "Credit card",
    summary:
      "A revolving line for everyday spending. Northbank sets the limit, the tier and the rate.",
    asks: { amount: false, term: false, asset: false, downPayment: false, purpose: true },
  },
  {
    type: "PERSONAL_LOAN",
    name: "Personal loan",
    summary: "A fixed amount over a fixed term, repaid monthly.",
    terms: [12, 24, 36, 48, 60],
    asks: { amount: true, term: true, asset: false, downPayment: false, purpose: true },
  },
  {
    type: "AUTO_LOAN",
    name: "Auto loan",
    summary: "Secured against the vehicle you are buying.",
    terms: [36, 48, 60, 72],
    asks: { amount: true, term: true, asset: true, downPayment: true, purpose: false },
  },
  {
    type: "MORTGAGE",
    name: "Mortgage",
    summary: "Secured against the property you are buying.",
    terms: [180, 240, 360],
    asks: { amount: true, term: true, asset: true, downPayment: true, purpose: false },
  },
];

export function creditProduct(type: string): CreditProduct | undefined {
  return CREDIT_PRODUCTS.find((product) => product.type === type);
}

/** How a product type reads on screen, for any application including deposits. */
export function productLabel(type: string): string {
  switch (type) {
    case "CHECKING_ACCOUNT":
      return "Checking account";
    case "SAVINGS_ACCOUNT":
      return "Savings account";
    default:
      return creditProduct(type)?.name ?? type;
  }
}
