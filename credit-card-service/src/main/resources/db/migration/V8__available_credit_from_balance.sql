-- Available credit is the limit less the balance, never more than the limit.
-- It used to be adjusted per operation while interest reset and clamped it,
-- so a card that interest took past its limit could be paid off into
-- available credit above its limit. Recompute any row that drifted.
UPDATE credit_cards
SET available_credit = GREATEST(credit_limit - current_balance, 0)
WHERE available_credit <> GREATEST(credit_limit - current_balance, 0);
