"use client";

import { useActionState, useEffect, useRef, useState } from "react";
import { Plus } from "lucide-react";
import {
  Button,
  FormError,
  SelectField,
  SuccessNote,
  TextField,
} from "@/components/ui/form";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { addBeneficiaryAction } from "@/features/payments/actions";
import { BENEFICIARY_IDLE, type BeneficiaryFormState } from "@/features/payments/state";

/**
 * Saving a payee, from the browser.
 *
 * Deliberately the only write on this page. Creating a payment moves money, and
 * `POST /api/payments` carries no idempotency record the way the transaction
 * endpoints do — so a lost response there could not be retried safely, and that
 * flow stays out of the console until it can. Saving a payee moves nothing.
 *
 * The account number lives in this form and nowhere else: it is typed, sent,
 * and the fields are cleared on success. Nothing is written to localStorage or
 * sessionStorage, and what comes back to confirm the save is already masked.
 */

const TYPES = [
  { value: "INTERNAL", label: "Within Northbank" },
  { value: "EXTERNAL_ACH", label: "Another US bank (ACH)" },
  { value: "WIRE", label: "Domestic wire" },
  { value: "SWIFT", label: "International (SWIFT)" },
];

export function AddBeneficiary() {
  const [open, setOpen] = useState(false);
  const [state, formAction, pending] = useActionState<BeneficiaryFormState, FormData>(
    addBeneficiaryAction,
    BENEFICIARY_IDLE,
  );
  const formRef = useRef<HTMLFormElement>(null);

  const fields = state.status === "invalid" ? state.fields : undefined;

  /*
   * Clearing on success is the point, not a nicety: the account number has
   * done its job and should not sit on screen afterwards. In an effect because
   * a ref must not be read during render.
   */
  useEffect(() => {
    if (state.status === "saved") formRef.current?.reset();
  }, [state]);

  return (
    <Card>
      <CardHeader
        title="Add a payee"
        description="Save someone you pay, so the details do not have to be entered again."
        action={
          <Button
            type="button"
            variant="secondary"
            onClick={() => setOpen((v) => !v)}
            aria-expanded={open}
            aria-controls="add-beneficiary-form"
          >
            <Plus aria-hidden="true" className="mr-1.5 h-4 w-4" />
            {open ? "Close" : "Add payee"}
          </Button>
        }
      />

      <CardBody>
        {state.status === "saved" ? (
          <SuccessNote>
            {state.name} saved, paying {state.maskedNumber}.
          </SuccessNote>
        ) : null}

        <div id="add-beneficiary-form" hidden={!open}>
          <form ref={formRef} action={formAction} className="mt-2 space-y-4" noValidate>
            {state.status === "failed" ? <FormError>{state.error}</FormError> : null}

            <div className="grid gap-4 sm:grid-cols-2">
              <TextField
                label="Payee name"
                name="name"
                required
                autoComplete="off"
                maxLength={100}
                error={fields?.name}
                disabled={pending}
              />
              <TextField
                label="Nickname"
                name="nickname"
                hint="Optional"
                autoComplete="off"
                maxLength={60}
                error={fields?.nickname}
                disabled={pending}
              />
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <TextField
                label="Account number"
                name="accountNumber"
                required
                inputMode="text"
                /*
                 * Off, explicitly. A browser is welcome to remember a name; it
                 * has no business filing an account number away under one.
                 */
                autoComplete="off"
                maxLength={34}
                error={fields?.accountNumber}
                disabled={pending}
              />
              <TextField
                label="Routing number"
                name="routingNumber"
                hint="Optional — nine digits"
                inputMode="numeric"
                autoComplete="off"
                maxLength={9}
                error={fields?.routingNumber}
                disabled={pending}
              />
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <TextField
                label="Bank name"
                name="bankName"
                hint="Optional"
                autoComplete="off"
                maxLength={100}
                error={fields?.bankName}
                disabled={pending}
              />
              <SelectField
                label="How they are paid"
                name="beneficiaryType"
                required
                defaultValue=""
                error={fields?.beneficiaryType}
                disabled={pending}
              >
                <option value="">Choose a method</option>
                {TYPES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </SelectField>
            </div>

            <input type="hidden" name="currency" value="USD" />

            <Button type="submit" pending={pending} disabled={pending}>
              {pending ? "Saving…" : "Save payee"}
            </Button>
          </form>
        </div>
      </CardBody>
    </Card>
  );
}
