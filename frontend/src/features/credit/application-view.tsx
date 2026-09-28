import { Detail, DetailList } from "@/components/ui/primitives";
import { formatCurrency, formatDate, formatPercent, humanise } from "@/lib/format";
import type { Application, Offer } from "@/types/api";

/** The product an application produced, once one really exists. */
export function productHref(application: Application): string | null {
  // PROVISIONED and a real id, or nothing. PROVISIONING means the product has
  // been asked for and not yet confirmed, and linking to it then would promise
  // a card or a loan that may not exist — which is the failure the whole
  // lifecycle was rebuilt to stop.
  if (application.status !== "PROVISIONED" || !application.productId) return null;

  switch (application.applicationType) {
    case "CREDIT_CARD":
      return `/cards/${application.productId}`;
    case "PERSONAL_LOAN":
    case "AUTO_LOAN":
    case "MORTGAGE":
      return `/loans/${application.productId}`;
    case "CHECKING_ACCOUNT":
    case "SAVINGS_ACCOUNT":
      return `/accounts/${application.productId}`;
    default:
      return null;
  }
}

export function OfferTerms({ offer }: { offer: Offer }) {
  return (
    <DetailList>
      {offer.approvedAmount !== null ? (
        <Detail label="Amount">{formatCurrency(offer.approvedAmount, offer.currency)}</Detail>
      ) : null}
      {offer.creditLimit !== null ? (
        <Detail label="Credit limit">{formatCurrency(offer.creditLimit, offer.currency)}</Detail>
      ) : null}
      {offer.cardTier ? <Detail label="Card">{humanise(offer.cardTier)}</Detail> : null}
      {offer.apr !== null ? <Detail label="APR">{formatPercent(offer.apr)}</Detail> : null}
      {offer.termMonths !== null ? (
        <Detail label="Term">{offer.termMonths} months</Detail>
      ) : null}
      {offer.monthlyPayment !== null ? (
        <Detail label="Estimated monthly payment">
          {formatCurrency(offer.monthlyPayment, offer.currency)}
        </Detail>
      ) : null}
      {offer.status === "OFFERED" && offer.expiresAt ? (
        <Detail label="Offer valid until">{formatDate(offer.expiresAt)}</Detail>
      ) : null}
    </DetailList>
  );
}

