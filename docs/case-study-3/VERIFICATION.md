# Verification record

Verified on **14 November 2026 (Asia/Kolkata)** with OpenJDK 21.0.13. The environment has the compiler module but no `javac` launcher, so the provided build script used its supported compiler-module fallback. No third-party Java dependencies were required.

## Commands

```bash
bash banking-java/scripts/test.sh
bash banking-java/scripts/demo.sh
```

## Observed automated result

**17 Java test groups passed**, covering:

1. Canonical money/account-number value equality, precision and currency compatibility.
2. Rejection of zero/negative amounts with no added postings.
3. Exact overdraft boundary and one-paisa excess.
4. Freeze, allowed frozen credits, unfreeze, zero-balance closure and terminal CLOSED behavior.
5. Private/final account fields, immutable entity versions and unmodifiable histories/maps.
6. INR 5,000 transfer conservation and matching debit/credit IDs.
7. Missing/same/closed/foreign-currency destinations with no source debit.
8. Exception after debit staging with no published partial state.
9. Duplicate transfer retries and rejection of conflicting request-ID reuse.
10. Compensating reversals, original-entry preservation and blocked reversal from a frozen account.
11. Concurrent double-spend requests against one account.
12. Competing freeze/withdraw commands with a legal serial result.
13. File persistence/restart, reconciliation and corruption detection.
14. A real child-process `Runtime.halt(99)` after staged debit; unchanged balances after restart.
15. Concurrent repository instances targeting one persistent file.
16. Rejection of balance/ledger mismatches during reconstruction.
17. Rejection of account removal and rewriting a posted ledger entry.

The scripted demonstration also completed successfully. Its observed key balances were:

| Step | Account A | Account B |
| --- | --- | --- |
| After opening deposits | INR 10,000.00 | INR 2,000.00 |
| After injected transfer failure | INR 10,000.00 | INR 2,000.00 |
| After INR 5,000 transfer and same-ID retry | INR 5,000.00 | INR 7,000.00 |
| After compensating reversal | INR 10,000.00 | INR 2,000.00 |
| After A's boundary withdrawal | INR -1,000.00 | INR 2,000.00 |
| After repayment, unfreeze and closure | INR 0.00, CLOSED | INR 2,000.00 |

Both saved accounts reloaded with reconciled ledger histories. The generated `work/demo.bank` and compiled classes are excluded from Git.

## Practical limits of this evidence

These checks exercise the Java model, process failure, local file persistence and cooperating-thread concurrency. They are not certification of a filesystem under every power-loss scenario, a distributed settlement system, a security audit or a live bank/UPI integration. The separate PaySmart web UI was not changed by this case-study addition, and these Java tests do not claim to be browser tests or tests of its Node backend.
