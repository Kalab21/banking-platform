import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LoginForm } from "@/features/auth/LoginForm";
import type { AuthFormState } from "@/features/auth/actions";

/**
 * The second step of signing in.
 *
 * A correct password is not a session when the account carries a second factor:
 * the backend answers `twoFactorRequired` and issues no token, and the form has
 * to ask for the code rather than behave as though the user is in. These tests
 * hold the form to that, because the copy on the profile page promises it.
 */

const submissions: FormData[] = [];
let nextState: AuthFormState = {};

vi.mock("@/features/auth/actions", () => ({
  loginAction: async (_prev: AuthFormState, formData: FormData): Promise<AuthFormState> => {
    submissions.push(formData);
    return nextState;
  },
}));

beforeEach(() => {
  submissions.length = 0;
  nextState = {};
});

async function signIn(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText("Username"), "ada.lovelace");
  await user.type(screen.getByLabelText("Password"), "DemoPassword123!");
  await user.click(screen.getByRole("button", { name: /sign in/i }));
}

describe("when the account carries a second factor", () => {
  it("asks for the authenticator code instead of signing the user in", async () => {
    const user = userEvent.setup();
    nextState = { twoFactorRequired: true, username: "ada.lovelace" };

    render(<LoginForm />);
    await signIn(user);

    expect(await screen.findByRole("heading", { name: /verify it's you/i })).toBeInTheDocument();
    expect(screen.getByLabelText(/authentication code/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^sign in$/i })).not.toBeInTheDocument();
  });

  it("resubmits the credentials with the code, so the second request is the one that succeeds", async () => {
    const user = userEvent.setup();
    nextState = { twoFactorRequired: true, username: "ada.lovelace" };

    render(<LoginForm />);
    await signIn(user);
    await screen.findByLabelText(/authentication code/i);

    // The challenge step carries the username in a hidden field and asks for
    // the password again: nothing about the first attempt is held anywhere.
    await user.type(screen.getByLabelText("Password"), "DemoPassword123!");
    await user.type(screen.getByLabelText(/authentication code/i), "123456");
    await user.click(screen.getByRole("button", { name: /verify/i }));

    expect(submissions).toHaveLength(2);
    const second = submissions[1];
    expect(second.get("username")).toBe("ada.lovelace");
    expect(second.get("password")).toBe("DemoPassword123!");
    expect(second.get("totpCode")).toBe("123456");
  });

  it("says the code was refused without saying which credential was wrong", async () => {
    const user = userEvent.setup();
    nextState = {
      twoFactorRequired: true,
      username: "ada.lovelace",
      error: "That authentication code was not accepted. Try the current code.",
    };

    render(<LoginForm />);
    await signIn(user);

    expect(await screen.findByText(/authentication code was not accepted/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/authentication code/i)).toBeInTheDocument();
  });
});

describe("when the account has no second factor", () => {
  it("does not ask for a code", async () => {
    const user = userEvent.setup();
    // The action redirects on success, so the form simply never enters the
    // challenge state.
    nextState = {};

    render(<LoginForm />);
    await signIn(user);

    expect(screen.queryByLabelText(/authentication code/i)).not.toBeInTheDocument();
  });
});
