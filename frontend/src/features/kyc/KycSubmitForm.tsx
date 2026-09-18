"use client";

import { useActionState } from "react";
import { submitKycAction, type KycFormState } from "@/features/kyc/actions";
import { Button, FormError, SelectField, SuccessNote, TextField } from "@/components/ui/form";

const INITIAL: KycFormState = {};

const DOCUMENT_TYPES = [
  { value: "PASSPORT", label: "Passport" },
  { value: "DRIVERS_LICENSE", label: "Driver's license" },
  { value: "NATIONAL_ID", label: "National ID" },
  { value: "PROOF_OF_ADDRESS", label: "Proof of address" },
];

export function KycSubmitForm() {
  const [state, action, pending] = useActionState(submitKycAction, INITIAL);

  return (
    <form action={action} className="space-y-4" noValidate>
      {state.error ? <FormError>{state.error}</FormError> : null}
      {state.success ? <SuccessNote>{state.success}</SuccessNote> : null}

      <SelectField
        label="Document type"
        name="documentType"
        required
        error={state.fields?.documentType}
        disabled={pending}
      >
        <option value="">Select a document</option>
        {DOCUMENT_TYPES.map((d) => (
          <option key={d.value} value={d.value}>
            {d.label}
          </option>
        ))}
      </SelectField>

      <TextField
        label="Document reference"
        name="documentRef"
        required
        hint="The identifier printed on the document"
        error={state.fields?.documentRef}
        disabled={pending}
      />

      <Button type="submit" pending={pending}>
        {pending ? "Submitting…" : "Submit for review"}
      </Button>
    </form>
  );
}
