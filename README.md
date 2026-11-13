# PaySmart 🇮🇳

A stylish full-stack Indian digital wallet sandbox, built with vanilla JavaScript, a Node.js API, and SQLite. The same API can run on a Cloudflare Worker with D1. No npm dependencies are needed for local development.

## Run locally

Requires Node.js 24 or later.

```bash
npm start
# Open http://127.0.0.1:3000
npm test
npm run build
```

The local server creates `data/paysmart.sqlite` automatically. `PORT`, `HOST`, and `PAYSMART_DATA_DIR` configure the server. Local accounts use a random HttpOnly, SameSite=Strict session cookie; retain the cookie to retain access to your sandbox wallet. Each session gets an isolated account. Local sessions are sandbox identities, not bank-grade authentication. Hosted private Sites use platform-injected ChatGPT identity headers instead of trusting client identity fields.

## Features

- Responsive INR dashboard, transaction search/filtering, receipts and CSV export.
- Durable wallet accounts, editable profile and merchant registry.
- Sandbox transfers to `priya@paysmart` and `rahul@paysmart`.
- Merchant payments to Swiggy, Amazon India, Starbucks, BigBasket, Zomato and DMart.
- Simulated top-ups, mobile recharges and bill payments.
- Sandbox verification code **123456** checked by the server. No real PINs or OTPs are collected.
- Atomic wallet updates using optimistic database version checks. Both debit and credit, transaction record, refund state and balanced journal are persisted together.
- Integer paise, positive amount validation, ₹1,00,000 per-transaction and daily outgoing limits, idempotency keys, one-time full merchant refunds, insufficient-balance rejection and per-user data isolation.
- Backend reports at `/api/reports`; category percentages and month comparisons in the visual dashboard are illustrative.
- Real UPI ID payment handoff through an encoded `upi://pay` link, stored separately as **Unverified** and excluded from sandbox balances.

## Real UPI payments

Select **To UPI ID**, enter a recipient and amount, and open the generated link on a phone with a UPI app installed. The UPI app verifies the payee and authorizes any actual payment. PaySmart only validates UPI ID syntax; it cannot establish whether a VPA exists or confirm the payee's identity. Never enter a UPI PIN into PaySmart.

External payments are **not verified or settled by this project**. Production payment acceptance, payee validation, refunds and reconciliation require merchant onboarding with a regulated payment provider, server-side order creation, provider signature checks and verified webhooks. No client-side “paid” action can credit the wallet. Sandbox refunds affect simulated money only. This project has no payment-provider credentials and makes no live payment or bank API calls.

## API

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/health` | Database API status |
| GET | `/api/state` | Current account, wallets, history, journal, external intents |
| GET | `/api/reports` | Balances and ledger consistency report |
| POST | `/api/profile` | Update display name |
| POST | `/api/transactions` | Verified sandbox payment/top-up/refund |
| POST | `/api/upi-intents` | Create external UPI handoff without moving wallet funds |

Payment body: `{ "type": "transfer", "name": "priya@paysmart", "amount": 10000, "key": "unique-request-id", "code": "123456" }`. Amounts are integer paise. Types: `transfer`, `merchant`, `topup`, `recharge`, `bill`, `refund`. Refunds also require `originalId` and the full original amount. Reuse the same key and payload after an uncertain response.

The account document stores wallets, transaction history and double-entry postings in a single atomically versioned SQL row. It prioritizes consistency for a small project; large-scale usage should normalize records and add pagination, retention, access recovery, rate limiting, operational monitoring, audited authentication/KYC and payment-provider reconciliation.

## Project structure

- `public/`: UI and API client.
- `server/domain.mjs`: payment rules and journal creation.
- `server/api.mjs`: shared API and D1 adapter.
- `server/local.mjs`: Node.js HTTP server and SQLite adapter.
- `drizzle/`: hosted schema migration.
- `scripts/build.mjs`: deterministic, dependency-free Worker bundle.
- `tests/`: domain and database API integration tests.

Opening transaction history is seeded demonstration data. Opening balances represent prior activity and do not derive from the displayed subset; new operations have balanced journal entries. Each authenticated hosted user has an isolated sandbox containing their own sample counterparties.
