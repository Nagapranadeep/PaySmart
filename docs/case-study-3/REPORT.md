# Case Study 3 — Bank Account and Money Transfers

## Problem analysis

The proposed design allows public fields and loosely coordinated service methods to produce invalid states. A caller could bypass withdrawal checks, change a balance directly, debit a frozen account, use floating-point values for money, or update one account without the other. Separate balance and history writes can also disagree after a crash or a concurrent request.

The improved design puts local rules inside account/value objects and cross-account rules inside an atomic transaction boundary. Domain objects have no public mutable financial fields. The Java implementation is in [`banking-java/`](../../banking-java/). PaySmart's existing web wallet remains a separate JavaScript subsystem; the Java module is the implementation assessed by this banking case study.

## Q1. Identification of domain invariants — 6 marks

| # | Business rule | Why it matters | Consequence of a violation | Enforcement |
| --- | --- | --- | --- | --- |
| 1 | Every deposit, withdrawal and transfer amount must be strictly positive. Zero is valid for a balance, not for an operation. | Gives commands an unambiguous direction and prevents meaningless postings. | A negative withdrawal could credit money; a negative deposit could debit money; zero entries pollute history. | `Money.requirePositive()`, account postings and transfer construction. |
| 2 | Amounts must have valid currency precision; arithmetic must be exact and currency-compatible. | INR uses two fractional digits. A transfer cannot silently add INR to USD or lose paise. | Rounding drift, incorrect balances, accidental FX and reconciliation failures. | Immutable `Money`, `BigDecimal`, currency-specific scale, `UNNECESSARY` rounding policy. |
| 3 | After each operation, `balance >= -overdraftLimit`; the agreed limit itself is non-negative and uses the account currency. | Enforces the authorized credit facility, including its exact boundary. | Unauthorized debt, credit exposure and incorrect fees. | `OverdraftLimit`, constructor validation, account debit and snapshot reconciliation. |
| 4 | A frozen account must never accept an outgoing debit, including transfer debits and reversal debits. | A freeze must apply to all debit paths, not just ATM withdrawals. | Funds can leave an account under restriction. | `BankAccount.post(DEBIT, ...)`; repository status guard. |
| 5 | A closed account cannot accept deposits, withdrawals, transfers or reopening. Closure requires ACTIVE status and exactly zero balance. | Makes closure terminal and prevents abandoning a positive balance or outstanding overdraft. | Stranded customer funds, lost debt, resurrected accounts and inconsistent lifecycle. | `close()`, state-transition methods and posting guards. |
| 6 | A same-bank transfer commits the debit, credit, two ledger entries and transfer record together, or publishes none of them. | Prevents the INR 5,000 crash scenario. | Missing credit, phantom funds or an incomplete audit trail. | One repository transaction and one atomic aggregate snapshot commit. |
| 7 | A completed transfer has equal debit/credit amounts in the same currency, with matching transfer ID and the correct source/destination. | Preserves total customer funds for a fee-free internal transfer. | Money creation/destruction or crediting the wrong account. | `BankSnapshot` checks both entries against each `FundTransfer`. |
| 8 | An account balance equals the sum of its immutable signed ledger postings, starting from zero; every running balance agrees too. | Makes the financial state explainable and auditable. | Statements disagree with available funds and errors remain undetected. | `BankAccount.reconcile()` on construction, operations and reload. Opening funds use a deposit entry. |
| 9 | Retrying a completed transfer request with the same ID and payload must not post it again; reuse with different details is rejected. | Handles uncertain commit responses safely. | Double debit/credit or a key being used to substitute another payment. | Persisted transfer request IDs, serialized lookup and payload comparison. |
| 10 | Account numbers and entity/entry IDs remain unique; source and destination must exist and differ. | Prevents ambiguous identity and invalid transfers. | Wrong account selection, duplicate histories or meaningless self-transfers. | Value-object validation, unique maps, snapshot checks and service lookup. |
| 11 | Posted ledger entries cannot be modified/deleted, and a full transfer can be reversed at most once by new compensating postings. | Preserves evidence rather than concealing mistakes. | Audit gaps, double refunds and inability to reconstruct balances. | Immutable entries, unmodifiable lists, repository prefix checks and linked reversal records. |
| 12 | Concurrent requests must be checked against the latest committed state and produce a legal serial outcome. | Two individually valid withdrawals must not jointly exceed available funds/overdraft. | Lost updates and concurrent overdraft violations. | Synchronized memory transactions; JVM lock plus OS file lock and reread for durable transactions. |

**Policy choice:** FROZEN accounts may receive positive credits, including repayment, but cannot send funds. CLOSED accounts cannot receive credits. A bank requiring a total freeze can tighten the credit rule. There are no fees or FX in this exercise, so the internal transfer conservation equation excludes neither hidden fees nor exchange adjustments.

## Q2. Entities and value objects — 6 marks

| Concept | Classification | Identity and equality | Lifecycle and mutability |
| --- | --- | --- | --- |
| Bank Account | Entity | Identified by stable `UUID id`; two versions with the same ID represent the same account even if status/balance differs. The account number is a separate unique value. | Open → active/frozen → closed. Java fields are private/final; operations return a new immutable version retaining the ID. Entity identity does not require mutable fields. |
| Account Number | Value object | Equality is the validated digit sequence, not a generated identity. Two independently created numbers with the same digits are equal. | Immutable; replacing the value creates another number. The application does not renumber an existing account. |
| Money | Value object | Equality is the canonical decimal amount and currency. `INR 1` and `INR 1.00` normalize to the same value. `INR 1` and `USD 1` differ. | Immutable; add/subtract return new values. Signed money can represent a negative balance; commands separately require positive amounts. |
| Ledger Entry | Entity, immutable after posting | Each entry has a unique UUID. Two distinct deposits of the same amount/time must remain distinct financial facts. `equals/hashCode` use entry ID. `samePosting` checks complete financial content for history preservation. | Created once on posting, retained for audit, never rewritten/deleted. Corrections get new IDs and link to the original. Immutability does not make it a value object. |
| Overdraft Limit | Value object | Equality is its non-negative `Money` amount/currency. The agreement amount has no independent lifecycle in this selected model. | Immutable, validated at construction. Limit changes are out of scope; a production credit agreement could itself be a separate entity. |
| Fund Transfer | Entity | Stable UUID plus a unique client request ID; equal-valued transfers are still separate instructions. | Same-bank transfer is recorded on atomic completion; it may subsequently become REVERSED when a distinct compensation is committed. Immutable versions retain transfer identity. |

Other concepts include `AccountStatus`, ledger direction, transaction/request IDs, a repository transaction and the immutable `BankSnapshot`. A customer's identity and authorization would be separate concerns in a production system; the selected Java module is a domain model/demo, not a customer-authentication server.

## Q3. Self-enforcing objects and Java design — 8 marks

### Selected API

```java
BankAccount account = BankAccount.open(
    new AccountNumber("1000000001"),
    new OverdraftLimit(Money.inr("1000.00")));
account = account.deposit(Money.inr("10000"), "Cash deposit");
account = account.withdraw(Money.inr("500"), "ATM withdrawal");
account = account.freeze();
// account.withdraw(...) now throws BankingException.
account = account.unfreeze();
// close() succeeds only when ACTIVE and balance is exactly zero.
```

These direct entity calls return detached immutable versions. The runnable application uses `BankService` to validate and persist the returned version through a repository transaction. Ignoring a returned account never silently changes the original account or stored state.

### Rules at their natural boundaries

- **Money precision and currency:** the `Money` constructor canonicalizes scale using `BigDecimal.setScale(..., UNNECESSARY)`. Excess precision is rejected instead of silently rounded. No `double` is used for financial amounts.
- **Positive operation amounts:** `post()` checks positivity before choosing credit/debit arithmetic. Balance construction allows zero/negative values where an agreed overdraft permits them.
- **Status:** `post()` rejects every CLOSED operation and every FROZEN debit. Transfer debits use that same method, so they cannot bypass the rule.
- **Overdraft:** the candidate balance is tested against `-limit` before a new account version is constructed. A balance of INR -1,000 is valid with a INR 1,000 limit; INR -1,000.01 is not.
- **Lifecycle:** `freeze()` requires ACTIVE; `unfreeze()` requires FROZEN; `close()` requires ACTIVE and zero balance. CLOSED is terminal.
- **Encapsulation:** all account fields are private/final; there are no balance/status setters. `List.copyOf`/`Map.copyOf` prevent callers from editing returned histories or aggregate maps.
- **Ledger agreement:** each successful posting creates a positive-amount entry with direction and resulting balance. Account construction reconciles all entries before exposing the version.
- **Persistence:** `BankRepository.transact()` commits the complete validated snapshot. A commit guard refuses deletion/replacement of accounts and modification/deletion of prior postings and transfer facts.

**Implemented classes:** [`Money`](../../banking-java/src/main/java/com/paysmart/bank/Money.java), [`BankAccount`](../../banking-java/src/main/java/com/paysmart/bank/BankAccount.java), [`OverdraftLimit`](../../banking-java/src/main/java/com/paysmart/bank/OverdraftLimit.java), [`BankService`](../../banking-java/src/main/java/com/paysmart/bank/BankService.java).

The repository interface separates storage from business behavior; memory and file repositories are interchangeable. Composition is used for money, limits and ledger entries. Inheritance is not forced onto value objects merely to satisfy an OOP checklist.

## Q4. Money transfer and aggregate consistency — 6 marks

### 1. What goes wrong after A is debited but B is not credited?

A loses access to INR 5,000, B is unpaid, and the total customer funds visible in the system are short by INR 5,000. The transfer status and statement may contradict the balances. A customer retry may debit A a second time if the system cannot recognize the original request. Concurrent transactions may worsen the discrepancy.

### 2. Which invariants are violated?

Transfer atomicity, conservation of funds, complete matching transfer postings, and possibly balance/history agreement and at-most-once transfer processing. A single-account balance could still match its debit entry while the **cross-account** transfer is broken; therefore local reconciliation alone is insufficient.

### 3. Design that prevents the loss

In the executable Java sample, the small bank is one logical aggregate represented by a `BankSnapshot`. The repository owns the read–validate–commit boundary:

1. Acquire its transaction lock; load the latest committed bank.
2. Find an existing request ID. Return its existing transfer only if the payload matches.
3. Validate both accounts, currencies, positive amount, source status and available overdraft.
4. Create a new source version with the debit, held locally in the transaction callback.
5. Create a new destination version with the credit.
6. Construct the completed transfer and validate both matching ledger entries.
7. Persist and publish **one complete snapshot**, containing both accounts, both entries and the transfer/request ID.
8. Return success only after the durable commit procedure.

For the INR 5,000 example: A starts at INR 10,000 and B at INR 2,000. Before a successful commit, both remain at those values. After commit, A is INR 5,000 and B is INR 7,000. There is no persisted half-transfer.

### 4. Failure handling

| Failure | Behavior |
| --- | --- |
| Invalid amount, frozen source, insufficient overdraft, closed destination or currency mismatch | Reject without committing. Existing balances, entries and transfer history remain unchanged. |
| Exception or process termination after staging the debit | Candidate source version was never published. Restart loads the previous complete bank. The suite tests a real child-process halt at this point. |
| Failure writing the temporary snapshot | Old bank file remains the committed state. An incomplete temporary file is never treated as the bank. |
| Process failure around atomic rename | With the documented filesystem guarantees, readers find the old or new complete snapshot, never one account from each. |
| Commit happened, but acknowledgment was lost or directory durability failed | Outcome is uncertain. Reload and retry the **same transfer request ID/payload**; a committed transfer is returned rather than posted again. Do not assume every exception proves rollback. |
| Corrupt persisted file | Reject load and require verified backup/recovery. Do not replace it with an empty bank. The checksum detects accidental corruption, not malicious tampering. |
| Error discovered after successful completion | Use an authorized compensating reversal, subject to current debit rules; never edit prior ledger entries. |

**Production alternative:** accounts may be separate aggregate roots in one banking database. A transaction coordinator can lock both rows in deterministic account-ID order (or use version checks), update both balances, insert both ledger postings and the transfer ID, then `COMMIT`. Any pre-commit failure rolls back the database transaction. A unique request-ID constraint handles retries. Calling two independent `save()` methods or synchronizing a Java method alone does not provide durable database atomicity.

The sample's file-based, bank-wide aggregate is deliberately simple and coarse-grained, not a claim that a retail bank should keep every customer in one file. Required filesystem guarantees and size/throughput limits are documented in the module README.

### 5. Different banking systems

Separate banks do not share this repository lock or local ACID transaction. Use a durable transfer state machine and the relevant regulated payment/settlement network:

- Atomically record the instruction and reserve/debit funds into an in-flight/clearing position, plus an outbox message.
- Dispatch with a stable end-to-end transfer ID; the receiving system uses an idempotent inbox/credit operation.
- Confirm completion only from an authenticated acknowledgment/settlement record. Retry delivery, not arbitrary new transfers.
- A timeout means **unknown/pending**, not automatically failed. Query status and reconcile before releasing/refunding funds.
- If definitively rejected, compensate/release the reservation exactly once. A late success must not coexist with an unconditional refund.
- Track customer balances **plus clearing/reserved funds** when checking conservation during settlement. The two customer balances alone need not balance at every intermediate moment.

A saga/outbox supports coordinated recovery and eventual consistency; it does not turn separate banks into one instantly atomic database. Two-phase commit is an option only where both systems explicitly support a shared coordinator and its operational tradeoffs. This project documents the cross-bank approach; it does not implement live settlement or claim UPI certification.

## Q5. Ledger management and design evaluation — 4 marks

### 1. Entry information

The implemented entry contains: unique entry ID; account entity ID and account number; monotonic account sequence; transaction/correlation ID; DEBIT/CREDIT direction; strictly positive amount and currency; resulting balance; timestamp (stored as `Instant`, displayed in IST); a reference/narrative; and optional original-entry ID for a reversal. A production ledger should additionally record authorized actor/channel, external settlement IDs and audit metadata under its retention policy.

### 2. Modify or delete a completed entry?

No. Posted entries are immutable financial facts. Changing/deleting them would hide what happened and invalidate statements and audit evidence. Add new compensating postings linked to the originals. Transfer status can advance to REVERSED, but the original postings remain unchanged. The Java repository also rejects history removal or rewriting at commit time.

### 3. Keep balances consistent

Accounts start at zero, with opening funds represented by a deposit entry. Every monetary operation produces the new balance and its ledger entry together. `reconcile()` checks:

```text
balance = sum(credits) - sum(debits)
each entry.balanceAfter = previous running balance + entry.signedAmount
transfer debit + transfer credit = 0, for the same amount/currency/transfer ID
```

Reconciliation runs on object reconstruction and snapshot loading, so corrupted/inconsistent state is rejected. Persisting both account state and ledger in one atomic commit prevents split writes. Background reconciliation, backups and alerts would complement this in production.

### 4. Large-history challenges

The demonstration copies/reconciles lists and rewrites a complete snapshot, so time, memory, lock duration and file I/O grow with history. There is a documented 128 MiB snapshot limit. It is unsuitable for a large bank without a different persistence design.

Use append-only indexed ledger tables, efficient account/transfer indexes, keyset pagination, partitioning/retention policies, asynchronous reporting/read models, and validated balance checkpoints with a last-applied ledger sequence. Checkpoints accelerate reads but do not authorize deleting audit history or trusting an unverified cached balance. Also plan backup/restore, reconciliation jobs, legal retention, access controls and detection of missing/duplicate postings.

## Design justification

The design prevents invalid local states by construction and validates complete aggregate transitions before persistence. Immutable entities preserve identity while protecting data; value objects centralize exact financial arithmetic. Business failures leave no partial candidate visible. Atomic persistence plus transfer idempotency covers crash recovery and uncertain outcomes. Compensating entries preserve history. These guarantees are exercised by executable tests rather than being assertions made only in the report.

## Requirement coverage

| Required submission | Evidence |
| --- | --- |
| Problem analysis | Opening section of this report. |
| Invariants | Q1: twelve rules with importance, consequences and implementation. |
| Entity/value classification | Q2: all six required concepts and identity/equality/lifecycle/mutability. |
| UML class diagram | [UML.md](UML.md), plus state and transfer sequence diagrams. |
| Java implementation | [`banking-java/src/main/java`](../../banking-java/src/main/java/com/paysmart/bank/); all five account operations and transfer/reversal services. |
| Design justification | Q3–Q5, persistence guarantees, failure table and documented limits. |
| Team presentation | [PRESENTATION.md](PRESENTATION.md): slides, demonstration script, role/contribution template. |
| Verification | [VERIFICATION.md](VERIFICATION.md): commands and observed test evidence. |

**Team participation is a human requirement:** each member must fill in their name, explain their assigned design/code, and record their actual contribution. The project supplies a template; it does not claim attendance or contributions that have not happened.
