# Team presentation and demonstration guide

Suggested duration: 10–12 minutes plus questions. Adapt the roles to the actual number of team members. Every member must explain a portion and answer questions; a generated role template is not evidence of participation.

## Slide outline

| Slide | Content | Evidence to show |
| --- | --- | --- |
| 1 | Problem: unsafe public fields, partial transfer, frozen debit, inconsistent history. | The five case-study incidents. |
| 2 | Invariants: positive amounts, exact INR, overdraft boundary, lifecycle, conservation and ledger agreement. | Q1 table; explain why zero balance differs from zero transaction amount. |
| 3 | Entities vs value objects. | Account/entry/transfer IDs versus account-number/money/limit equality. |
| 4 | UML and account lifecycle. | `UML.md`; private final account fields and allowed state transitions. |
| 5 | Self-enforcing Java behavior. | `Money`, `BankAccount.post`, `freeze`, `unfreeze`, `close`. |
| 6 | INR 5,000 transfer and failure boundary. | Service staging plus one repository commit. |
| 7 | Ledger, reversal and reconciliation. | Two transfer entries; new compensating entries; originals retained. |
| 8 | Tests, concurrency, real process crash and persistence. | `BankingTests`; test output; restart demo. |
| 9 | Different banks and production limits. | Pending settlement, outbox/idempotency/reconciliation; no false ACID claim. |
| 10 | Team contribution and conclusion. | Completed contribution record with real names and evidence. |

## Prepare the demonstration

From the repository root:

```bash
bash banking-java/scripts/test.sh
bash banking-java/scripts/demo.sh work/presentation-1.bank
```

Use a new filename for a first run. Reusing a filename deliberately reloads and displays the existing bank without charging it again. Store any screen recording under your team's normal submission location, not in the production customer ledger.

## Live demonstration narrative

1. **Open and deposit.** A starts at INR 10,000 with an INR 1,000 overdraft. B starts at INR 2,000 without overdraft.
2. **Crash scenario.** Inject an exception after the debit is staged. Read A/B again: still INR 10,000 / INR 2,000. Explain that the candidate A was never persisted.
3. **Successful transfer.** Transfer INR 5,000. A/B become INR 5,000 / INR 7,000. Show the shared transaction ID and matching debit/credit.
4. **Retry.** Repeat with the same request ID. No new debit occurs. Explain why a changed amount with the same ID is rejected.
5. **Reverse.** Add opposite postings linked to the original transfer/entries. A/B return to INR 10,000 / INR 2,000; original entries remain visible.
6. **Invalid amounts.** Show zero withdrawal and negative deposit being rejected before any ledger append.
7. **Overdraft.** Withdraw INR 11,000 from A: balance is exactly INR -1,000 and is valid. A further INR 0.01 is rejected.
8. **Freeze.** Freeze A and reject a debit. Credit INR 1,000 to repay the overdraft under the explicit frozen-credit policy. Unfreeze and close at zero.
9. **Closed account.** Reject both a new deposit and reopening. Show that the status is terminal.
10. **Persistence/reconciliation.** Reload from the file and print each account's history. Explain why balance equals signed entry totals.
11. **Real process failure.** Point to the test that runs a separate JVM, halts after staging the debit and verifies unchanged balances after restart. This is distinct from merely catching an exception.
12. **Scope.** Show PaySmart's existing web dashboard as a separate product demo; state that the assessed banking rules execute in the Java module, not the Node wallet backend.

## Optional operator CLI

The CLI permits arbitrary simulated-account operations rather than only the scripted demonstration. Use a fresh filename and prefix each command with:

```bash
bash banking-java/scripts/cli.sh --file work/team-lab.bank
```

Append one of:

```text
open 1000000001 1000
open 1000000002 0
deposit 1000000001 10000 Opening cash deposit
transfer 1000000001 1000000002 5000 team-transfer-1
accounts
ledger 1000000001
freeze 1000000001
withdraw 1000000001 1 Should be rejected
unfreeze 1000000001
transfers
```

Copy a transfer UUID from `transfers` to use `reverse TRANSFER_UUID UNIQUE_REQUEST_ID`. This is a trusted-operator educational CLI, not an authenticated banking terminal. All amounts are simulated INR.

## Contribution record — complete with actual participation

| Team member | Analysis responsibility | Design/code responsibility | Presentation/demo responsibility | Actual contribution evidence |
| --- | --- | --- | --- | --- |
| Fill in name | Invariants and consequences | Money and overdraft validation | Slides 1–2; amount/limit tests | Commit, review notes or meeting record |
| Fill in name | Entity/value classification | Account lifecycle and encapsulation | Slides 3–5; freeze/close demo | Commit, review notes or meeting record |
| Fill in name | Transfer failure analysis | Repository, atomic commit and retries | Slide 6; crash and concurrency tests | Commit, review notes or meeting record |
| Fill in name | Ledger, scale and cross-bank analysis | Reversal/reconciliation tests and diagrams | Slides 7–10; ledger and limitations | Commit, review notes or meeting record |

For a smaller team, combine responsibilities; for a larger team, split tests, UML and storage analysis into additional named roles. Record real contributions rather than assigning credit retroactively.

## Likely viva questions

- **Why not `double`?** Binary floating-point does not exactly represent many decimal monetary amounts; `BigDecimal` with explicit currency precision avoids that drift.
- **Can a balance be negative?** Yes, down to the agreed overdraft; operation amounts must still be positive.
- **Why is an immutable ledger entry an entity?** Its posting identity matters; equal amounts do not mean the same historical event.
- **Does `synchronized` alone survive a crash?** No. It handles in-process concurrency; durable atomic commit and recovery are separate responsibilities.
- **What if success was committed but the client saw an error?** Reread/retry with the same transfer request ID and payload. Never blindly create another transfer.
- **Why not edit the original debit to fix it?** That destroys evidence; add authorized compensating postings instead.
- **Are both accounts aggregate roots?** In a larger bank they often are. The sample deliberately uses one small-bank aggregate; a production database transaction can coordinate two account aggregates under explicit consistency policy.
- **Can this file repository replace a bank's core system?** No. It is a testable educational implementation with explicit filesystem, size, security and scalability limits.
- **Does the web UI prove Java is running?** No. The CLI/demo/tests run Java. The existing web wallet is clearly labeled as a separate JavaScript implementation.

## Before submission

- Replace team-name placeholders and attach actual contribution evidence.
- Every member runs the demo and can explain their code/diagram section.
- Include the report, UML, source code, observed test results and presentation material.
- Keep stated implementation guarantees separate from proposed production/cross-bank architecture.
