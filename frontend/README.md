# Banking Platform — Frontend

Next.js console for the `banking-platform` microservices backend. Part of the
[banking-platform](https://github.com/Kalab21/banking-platform) repository; see the root
README for the full system.

## How it talks to the backend

```
Browser  ──►  Next.js server  ──►  API Gateway (:8080)  ──►  Spring Boot services
```

Every request to the banking API is made **server-side** by the Next.js process, in React
Server Components and Server Actions. Two consequences worth noting:

- **The JWT never reaches browser JavaScript.** It lives in an httpOnly, SameSite=Lax cookie
  written by a server action, so an XSS bug cannot read it the way it could read
  `localStorage`. The cookie's lifetime comes from the token's own `expiresIn`.
- **No CORS configuration was needed on the gateway**, because the browser never calls it
  directly. The backend was not modified to support this frontend.

No service is ever addressed directly — `src/lib/api/client.ts` is the single outbound HTTP
layer and it only knows the gateway's base URL.

## Layout

```
src/
├── app/
│   ├── (auth)/          # login, register — no app chrome
│   └── (app)/           # authenticated shell: dashboard, accounts, loans, cards, admin
├── components/
│   ├── ui/              # primitives (cards, tables, badges, form fields)
│   └── layout/          # sidebar, sign-out, wordmark
├── features/            # one folder per domain: server actions + client components
├── lib/
│   ├── api/             # client.ts (fetch + errors), banking.ts (typed endpoints)
│   ├── session.ts       # cookie session, requireSession / requireStaffSession
│   ├── jwt.ts           # decode only — the gateway verifies
│   ├── validation.ts    # zod schemas mirroring backend Bean Validation
│   └── format.ts        # currency, dates, card/account masking
└── types/api.ts         # types mirroring the backend DTOs
```

## Running

```bash
npm install
cp .env.example .env.local     # point API_GATEWAY_URL at your gateway
npm run dev                    # http://localhost:3000
```

The backend must be reachable. From the repository root:

```bash
mvn clean package
docker compose up -d
```

## Checks

```bash
npm run lint        # ESLint (next/core-web-vitals + next/typescript)
npm run typecheck   # tsc --noEmit
npm run test        # Vitest + React Testing Library
npm run build       # production build
```

## Notes on scope

- **2FA is enforced at sign-in, and enrolment is optional.** With it enabled, `POST
  /api/auth/login` answers `twoFactorRequired` and issues no token until a valid TOTP code
  is presented; the login form swaps to a code prompt and resubmits. Nothing requires a
  customer to enrol in the first place.
- **Card numbers are always masked.** The backend returns `cardNumber` in full on
  `CreditCardResponse`; `maskCardNumber` is applied everywhere it is rendered.
- **KYC review is per customer.** There is no "all pending documents" endpoint, so the staff
  page looks a customer up by id instead of showing an invented queue.
- **Charts are drawn from recorded values only.** The balance trend plots real `balanceAfter`
  figures; below four transactions the panel shows a summary instead of implying a trend.
