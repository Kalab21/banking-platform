import { humanise } from "@/lib/format";
import type { CreditCard } from "@/types/api";

/**
 * A card, drawn as a card.
 *
 * Everything printed on it is a field the API actually returns: the masked
 * number, the product tier and the status. There is no network logo, no expiry
 * date, no CVV and no embossed cardholder name — the backend does not have
 * them, and a card face that shows an invented expiry is a lie in the shape of
 * a reassurance.
 *
 * The face is decorative. Every value on it is repeated as real text next to
 * the component, so nothing here is the only way to read a figure.
 */
const TIERS: Record<string, string> = {
  STANDARD: "from-navy to-navy-soft",
  GOLD: "from-[#3b3524] to-[#6b5a2e]",
  PLATINUM: "from-[#2a3340] to-[#4a5766]",
};

export function VirtualCard({ card }: { card: CreditCard }) {
  const gradient = TIERS[card.cardType] ?? TIERS.STANDARD;

  return (
    <div
      aria-hidden="true"
      className={`relative flex aspect-[1.586/1] w-full max-w-sm flex-col justify-between overflow-hidden rounded-[var(--radius-card)] bg-gradient-to-br ${gradient} p-5 text-white shadow-[0_8px_24px_-12px_rgba(16,24,40,0.45)]`}
    >
      <span className="pointer-events-none absolute -right-16 -top-20 h-56 w-56 rounded-full bg-white/10 blur-2xl" />

      <div className="relative flex items-start justify-between">
        <span className="text-sm font-semibold tracking-tight">Northbank</span>
        <span className="text-[0.6875rem] font-medium uppercase tracking-wider text-white/70">
          {humanise(card.cardType)}
        </span>
      </div>

      {/* A chip, drawn rather than imported. */}
      <span className="relative mt-2 h-7 w-9 rounded-[5px] bg-gradient-to-br from-[#e8d9a0] to-[#c9a94e]" />

      <div className="relative">
        <p className="tabular text-lg font-medium tracking-[0.12em]">{card.maskedCardNumber}</p>
        <p className="mt-1 text-[0.6875rem] uppercase tracking-wider text-white/60">
          {humanise(card.status)}
        </p>
      </div>
    </div>
  );
}
