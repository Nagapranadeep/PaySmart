# PaySmart 🇮🇳

A full-stack Indian payment platform with a dark fintech dashboard, a mobile payment interface, registration and login, durable wallets, a double-entry transaction journal, and real UPI app handoff.

**Live app:** https://paysmart-india-wallet.rareimpala8.chatgpt.site  
**Source:** https://github.com/Nagapranadeep/PaySmart

## Java banking case study 3

The repository also includes a **runnable Java 21 banking module** covering overdrafts, deposits/withdrawals, frozen/closed accounts, atomic transfers, immutable ledger history, reversals, reconciliation, concurrency and crash recovery.

- [Q1–Q5 submission report and coverage matrix](docs/case-study-3/REPORT.md)
- [UML class, state and sequence diagrams](docs/case-study-3/UML.md)
- [Java module and run instructions](banking-java/README.md)
- [Team presentation and contribution template](docs/case-study-3/PRESENTATION.md)
- [Observed verification results](docs/case-study-3/VERIFICATION.md)

```bash
npm run test:java
npm run demo:java
# Optional persistent account-management CLI:
bash banking-java/scripts/cli.sh
```

The existing web frontend/backend remains JavaScript. The assessed Java banking rules run in the separate module; the website does not claim to have banking overdraft/freeze/close controls. Real team-member names and contribution evidence must be completed in the presentation template.

## Start locally

Requires **Node.js 24+**. The local server uses built-in HTTP, Web Crypto, and SQLite modules. Run `npm ci` to install the remote-database client used by Vercel and its tests. QR generation and decoding libraries are vendored with their licenses.

```bash
npm ci
npm start
# Visit http://127.0.0.1:3000 and create an account.
npm run build
npm test
```

`PORT`, `HOST`, and `PAYSMART_DATA_DIR` configure the server. The default database is `data/paysmart.sqlite`, excluded from Git. Versioned schema migrations run automatically on local startup. Hosted Sites use Cloudflare D1; the deterministic build produces an ESM Worker with embedded frontend assets.

## Vercel deployment

The repository includes a Vercel Node.js API adapter with persistent Turso/libSQL storage. See [deployment instructions](docs/VERCEL.md) for the required Vercel account connection, database environment variables and production checks. Deploying requires actual Vercel access; the configuration alone is not a live deployment.

## Product features

- Responsive desktop dashboard and mobile navigation, with dark/light themes.
- Real registration, login and logout; isolated database records per account.
- Salted PBKDF2-SHA256 password hashes (100,000 iterations), 12-character registration minimum, random session secrets stored only as hashes, seven-day HttpOnly/SameSite=Strict cookies, Secure cookies over HTTPS, expired-session checks, revocation and login throttling.
- INR formatting, Indian Standard Time, Indian merchants, transaction search/filtering, receipts, CSV export and data-driven charts.
- Personal sandbox wallet and a private network of sample counterparties.
- Sandbox wallet transfers to `priya@paysmart` and `rahul@paysmart`.
- Merchant payments to Swiggy, Amazon India, Starbucks, BigBasket, Zomato and DMart.
- Simulated bank top-ups, mobile recharges and bill payments.
- Explicit review and verification screen. Sandbox code: **123456**. Never enter a real OTP or UPI PIN.
- ₹1–₹1,00,000 per transaction, ₹1,00,000 daily outgoing limit, integer paise, insufficient-balance checks, payload-aware idempotency and one-time full merchant refunds.
- Atomic persistence of both wallet balances, payment status and balanced journal entries through optimistic SQL version checks. Concurrent HTTP requests and independent worker threads cannot lose updates.
- UPI payment link creation, camera QR scanning, QR image upload, pasted-link parsing, receive-money QR generation and QR downloads.

## College-demo bank linking

Choose **My wallets → Add bank** to demonstrate HDFC, SBI or ICICI account linking using the displayed demo OTP **123456**. Select a fictitious savings/current account, set a default, or remove it. Records persist per signed-in user. This does not contact a bank or change wallet funds.

See [the flow, API rules and verification](docs/DEMO-BANK-LINKING.md). No real bank details or OTPs should be entered.

## Real UPI: supported scope

Select **To UPI ID** or **Scan & pay**, review the recipient and amount, then open the link on a phone with a compatible UPI app. The bank/UPI app authorizes any actual transfer. Desktop users can scan the generated payment QR with their phone.

PaySmart validates UPI ID syntax and INR amounts. It does **not** verify that a VPA exists or establish recipient identity; users must confirm those in their UPI app. Receive-money QR codes use a real UPI ID supplied by the account holder. The `@paysmart` sample wallet IDs are for internal simulation, not registered live VPAs.

External requests remain **Unverified**, are stored separately, and never change the sandbox wallet balance. Opening an app or clicking a client-side button cannot prove payment success. This is an original educational product, not affiliated with Google Pay, Navi, NPCI or any bank.

Live payment acceptance, account discovery, VPA validation, bank balances, KYC, real refunds, settlement and reconciliation require onboarding with a regulated payment provider and verified server-side callbacks. No provider credentials are configured. The project does not impersonate a bank or collect bank PINs. Password recovery/email delivery is not configured; the help page explains that limitation. Camera scanning requires HTTPS or localhost, camera permission and a capable browser; image upload and manual link entry are alternatives.

## OOP concepts in the running backend

| Concept | Actual implementation |
| --- | --- |
| Inheritance | `PersonalAccount`, `MerchantAccount`, `SettlementAccount` extend `WalletAccount`; `MobileRecharge` extends `BillPayment`. |
| Encapsulation | `WalletAccount.#balance` is private. Only validated debit/credit operations change it. |
| Abstraction | `PaymentMethod.prepare()` defines the payment-method contract. |
| Polymorphism | Wallet transfers, merchant payments, bills, top-ups, refunds and UPI handoffs implement different `prepare()` behavior. |
| Interfaces / contracts | `PaymentProcessor.register()` requires a `PaymentMethod`; SQL store adapters implement the same persistence operations. |
| Collections | Strategy registry uses `Map`; transaction, external intent and journal collections are persisted per account. |
| Concurrency / multithreading | Compare-and-swap SQL updates; a test runs real `worker_threads` against one SQLite database. |
| Exception handling | `DomainError` maps validation/consistency failures to meaningful API errors. |
| Extensibility | Register a new strategy without changing the payment processor's caller. |

The account aggregate stores its wallets, payment history and journal in one versioned SQL row. Both halves of a transfer commit together, or neither commits. The frontend cannot directly set balances. A rejected version causes a bounded reread/retry with the same idempotency key.

## API

| Method | Endpoint | Purpose |
| --- | --- | --- |
| POST | `/api/auth/register` | Create account and session |
| POST | `/api/auth/login` | Verify credentials and start session |
| GET | `/api/auth/me` | Current authenticated user |
| POST | `/api/auth/logout` | Revoke current session |
| GET | `/api/state` | Current user's profile, wallets, history, journal and UPI requests |
| GET | `/api/reports` | Wallet summaries and ledger balance check |
| GET | `/api/health` | Authenticated API health |
| POST | `/api/profile` | Update display name and optional receive UPI ID |
| POST | `/api/transactions` | Verified sandbox payment/top-up/refund |
| POST | `/api/upi-intents` | External UPI link, without wallet movement |

Example sandbox body:

```json
{
  "type": "transfer",
  "name": "priya@paysmart",
  "amount": 10000,
  "key": "unique-payment-request-id",
  "code": "123456"
}
```

Amounts are integer paise. Refunds use `type: "refund"`, the original full amount and `originalId`. After an uncertain network response, reuse the same key and payload to prevent duplicate payments.

## Project map

```text
public/           UI, UPI parser, locally vendored QR libraries
server/models.mjs Account classes, payment methods and strategy registry
server/domain.mjs Transaction validation and balanced journal creation
server/auth.mjs   Password hashing, sessions, login throttling
server/api.mjs    Shared API and D1 account store
server/local.mjs  Node HTTP server, SQLite store and migrations
scripts/build.mjs Deterministic standalone Worker build
drizzle/          Versioned SQL migrations
tests/            Authentication, wallets, OOP, QR, concurrency and Worker tests
```

Opening balances and six historical transactions are clearly seeded sandbox data. They represent earlier activity and are not reconstructed from the visible history. New transactions write balanced journal postings. Refunds of seeded historical merchant payments use the sandbox settlement account. Each registered user gets an isolated sample counterparty network; this is not a live person-to-person banking network.

For a production financial service, normalize and paginate large ledgers, add operational monitoring and retention, integrate audited identity/KYC and account recovery, and use a regulated payment provider for real settlement. The existing persistence design is aimed at a small educational project.

### Third-party QR code libraries

- `qrcode-generator` 1.4.4 — Kazuhiko Arase, MIT license.
- `jsQR` 1.4.0 — Apache-2.0; see `public/vendor/LICENSE-jsQR.txt`.
