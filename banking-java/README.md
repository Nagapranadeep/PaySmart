# Java Banking Module — Case Study 3

This is the runnable **Java implementation** for the Bank Account and Money Transfers case study. It sits alongside the existing JavaScript PaySmart web app; it does not replace that app's backend or claim the web wallet has banking overdrafts or freeze/close controls.

## Run

Use Java 21 with the compiler module / a JDK. No Maven, Gradle, third-party dependencies, database installation or real banking credentials are needed.

From the repository root, on Linux/macOS/WSL:

```bash
bash banking-java/scripts/test.sh
bash banking-java/scripts/demo.sh
```

Or run the repository shortcut:

```bash
npm run test:java
```

For a new presentation run, choose a **new snapshot filename**:

```bash
bash banking-java/scripts/demo.sh work/presentation-1.bank
```

Paths passed to `demo.sh` are relative to `banking-java/`. Reopening an existing file prints the persisted accounts and reconciled ledger without repeating the demonstration transactions. Generated classes and financial fixtures stay in ignored `work/`.

On Windows PowerShell with JDK 21, from `banking-java/`:

```powershell
New-Item -ItemType Directory -Force work/classes | Out-Null
$sources = (Get-ChildItem src -Recurse -Filter *.java).FullName
javac --release 21 -encoding UTF-8 -d work/classes $sources
java -cp work/classes com.paysmart.bank.BankingTests
java -cp work/classes com.paysmart.bank.BankingDemo
```

## Persistent operator CLI

Run `bash banking-java/scripts/cli.sh` for commands. For example:

```bash
bash banking-java/scripts/cli.sh --file work/team.bank open 1000000001 1000
bash banking-java/scripts/cli.sh --file work/team.bank open 1000000002 0
bash banking-java/scripts/cli.sh --file work/team.bank deposit 1000000001 10000 Opening cash deposit
bash banking-java/scripts/cli.sh --file work/team.bank transfer 1000000001 1000000002 5000 unique-request-1
bash banking-java/scripts/cli.sh --file work/team.bank ledger 1000000001
```

Commands also include `withdraw`, `freeze`, `unfreeze`, `close`, `reverse`, `accounts` and `transfers`. Invalid commands exit with code 2. This is a trusted-operator simulated banking interface; the web app's login is not an authorization layer for this CLI.

## What the demo proves

1. Open accounts A/B and deposit INR 10,000 / INR 2,000.
2. Fail after staging a INR 5,000 debit: both persisted balances remain unchanged.
3. Successfully transfer INR 5,000: A = INR 5,000, B = INR 7,000.
4. Retry the same request: no second debit or credit.
5. Reverse with compensating postings: original ledger entries remain intact.
6. Reject zero/negative amounts and withdrawals one paisa beyond the agreed overdraft.
7. Freeze, reject outgoing debits, permit incoming repayment by explicit policy, unfreeze, settle to zero and close.
8. Reject operations on a closed account and reconcile the saved bank after restart.

The test suite additionally kills a **separate Java process** with `Runtime.halt(99)` after debit staging, runs genuine concurrent threads, tries corrupt snapshots, and attempts to rewrite posted ledger history.

## Model and consistency

- Immutable value objects: `Money` (`BigDecimal` + `Currency`), `AccountNumber`, `OverdraftLimit`.
- Immutable-version entities: `BankAccount`, `LedgerEntry`, `FundTransfer`; entity equality follows stable UUIDs.
- `BankAccount` has private final fields and no setters. Its behavior returns a new validated version; only `BankService` asks a repository to commit it.
- `BankSnapshot` represents one small bank's aggregate state, with a commit guard against deleting/replacing accounts, rewriting ledger entries or removing transfers.
- `BankRepository` is an interface with memory and durable file implementations.
- `AtomicFileBankRepository` serializes cooperating threads/processes, rereads state under the lock, stages a complete checksummed snapshot, forces it to disk and atomically renames it. It does not fall back to a non-atomic overwrite.
- The durable commit publishes both account versions, both ledger entries and the transfer/request ID together. Neither half of a staged transfer is visible on its own.

### Supported failure guarantee and limits

On a supported **single-host filesystem with atomic rename, file locking and directory fsync**, readers observe the old or new complete snapshot. A failure after rename but before acknowledgment is an uncertain result: reload and retry a transfer with the **same request ID**. Deposits/withdrawals in this selected sample have no separate idempotency key; inspect the ledger before manually retrying those after an uncertain I/O result.

The checksum detects accidental corruption; it is not a digital signature and cannot prevent a malicious actor with write access from replacing a file. OS access controls, backups and operational recovery are still necessary. A failed checksum is rejected, not silently reset to an empty bank. Filesystems without the required durability primitives are unsupported.

This intentionally coarse aggregate copies and validates history and serializes operations for clarity. For a real bank, use a transactional database, per-account row/version locks, unique transfer keys, append-only ledger tables, indexed/paginated history, recovery tooling and audited authorization. Cross-bank transactions require settlement coordination rather than a shared file lock.

## Submission pack

- [Requirement coverage and Q1–Q5 report](../docs/case-study-3/REPORT.md)
- [UML and sequence diagrams](../docs/case-study-3/UML.md)
- [Presentation and team contribution template](../docs/case-study-3/PRESENTATION.md)
- [Verification record](../docs/case-study-3/VERIFICATION.md)
