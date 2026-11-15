# College demonstration: bank-account linking

PaySmart includes a clearly labelled simulation of UPI-style account linking for **HDFC Bank, State Bank of India (SBI), and ICICI Bank**. It does not connect to those banks or constitute bank verification.

## Demonstration steps

1. Sign in and open **My wallets** (on a phone, open Home and tap the bank card).
2. Select **Add bank / Link demo bank**.
3. Confirm the fictitious-account notice and choose HDFC, SBI or ICICI.
4. Enter the displayed **demo OTP `123456`**. No SMS is sent; the displayed mobile number is a fixture.
5. Choose a sample savings or current account, then save it.
6. See **Demo linked** status, set a default demo account, refresh to confirm persistence, or remove the account.

The default is a preference for the demonstration bank list; it does not authorize a real debit or change the sandbox wallet's funding source. Linking/removing accounts never changes wallet balances or ledger history.

## Server rules

- Authentication and account ownership are required for every endpoint.
- Demo consent is required before a confirmation session is created.
- Sessions expire after five minutes and permit up to five incorrect OTP attempts.
- Incorrect attempts are persisted in the same versioned account aggregate.
- Account choices are server-defined fixtures for the selected bank; the client cannot submit a different bank's account or a `bank_verified` status.
- Repeated linking of the same selected account does not create a duplicate.
- At most one saved demo account is the default. Removing it promotes another saved account if one exists.
- Internal challenge records are excluded from the normal account-state response.
- Account state uses the existing atomic compare-and-swap database update, including when requests arrive concurrently.
- Older user records without bank fields work without a schema migration.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/banks` | Supported demo banks and simulation mode |
| POST | `/api/banks/start` | Begin a consented demo confirmation |
| POST | `/api/banks/verify` | Check the displayed demo OTP |
| POST | `/api/banks/link` | Save a confirmed, server-defined sample account |
| POST | `/api/banks/default` | Select a default demo account |
| POST | `/api/banks/unlink` | Remove a demo account |

Seven automated bank-specific test groups cover consent, allowed banks, confirmation, wrong attempts, expiry, duplicate linking, default/removal behavior, older records, data isolation and unchanged funds. Browser verification covers desktop and a 390px phone viewport, all three banks, wrong OTP handling, refresh persistence, default selection and removal.

## Scope

Only sample account suffixes and sample IFSC values are used. The flow does not request real account numbers, phone numbers, debit-card credentials, bank OTPs or UPI PINs. Genuine bank linking would require an approved PSP/sponsor-bank or supported banking-provider integration, device/mobile verification, bank account discovery and provider-confirmed registration. Existing external UPI payment links still open an installed UPI app and are separate from this simulation.
