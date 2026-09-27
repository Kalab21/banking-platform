-- What has been paid against each instalment.
--
-- A repayment below the instalment marked it PARTIAL and nothing ever came
-- back to it, and the loan closed as PAID_OFF once no instalment was still
-- PENDING -- so 48 payments of 0.01 closed a 48-month loan with the whole
-- principal owed. Collecting the rest of an instalment needs to know how much
-- of it has been paid, and interest is paid first, so this one figure also
-- says how much of the instalment's interest is still due.
ALTER TABLE amortization_schedules
    ADD COLUMN amount_paid NUMERIC(19, 2) NOT NULL DEFAULT 0;

-- Backfill from the repayments recorded against each instalment. Early
-- payoffs settle the whole loan and are not an instalment payment.
UPDATE amortization_schedules s
SET amount_paid = paid.total
FROM (
    SELECT loan_id, payment_number, SUM(amount) AS total
    FROM loan_repayments
    WHERE is_early_payoff = FALSE AND payment_number IS NOT NULL
    GROUP BY loan_id, payment_number
) paid
WHERE s.loan_id = paid.loan_id AND s.payment_number = paid.payment_number;
