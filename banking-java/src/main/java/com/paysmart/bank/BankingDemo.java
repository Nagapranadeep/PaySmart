package com.paysmart.bank;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Presentation-ready, repeatable case-study walkthrough; does not touch real banking rails. */
public final class BankingDemo {
    private static final AccountNumber A = new AccountNumber("1000000001");
    private static final AccountNumber B = new AccountNumber("1000000002");
    public static void main(String[] args) {
        Path path = Path.of(args.length == 0 ? "work/demo.bank" : args[0]);
        var repository = new AtomicFileBankRepository(path); var bank = new BankService(repository);
        System.out.println("PAYSMART | JAVA BANKING CASE STUDY 3 | INR | Asia/Kolkata");
        System.out.println("Persistent snapshot: " + path.toAbsolutePath());
        if (!bank.snapshot().accounts().isEmpty()) {
            System.out.println("Existing bank loaded and reconciled; no demonstration transactions repeated.");
            report(bank); return;
        }
        bank.openAccount(A, new OverdraftLimit(Money.inr("1000")));
        bank.openAccount(B, new OverdraftLimit(Money.inr("0")));
        bank.deposit(A, Money.inr("10000"), "Opening cash deposit");
        bank.deposit(B, Money.inr("2000"), "Opening cash deposit");
        System.out.println("\n1. Deposit: A = INR 10000.00; B = INR 2000.00");
        var failing = new BankService(repository, () -> { throw new BankingException("Injected failure after staged debit"); });
        rejected("2. Failure between debit and credit", () -> failing.transfer(A, B, Money.inr("5000"), "case-study-transfer-5000"));
        System.out.println("   After failure: A = " + bank.account(A).balance() + "; B = " + bank.account(B).balance());
        FundTransfer transfer = bank.transfer(A, B, Money.inr("5000"), "case-study-transfer-5000");
        bank.transfer(A, B, Money.inr("5000.00"), "case-study-transfer-5000");
        System.out.println("3. Transfer + safe retry: A = " + bank.account(A).balance() + "; B = " + bank.account(B).balance());
        bank.reverseTransfer(transfer.id(), "case-study-reversal");
        System.out.println("4. Reversal adds entries: A = " + bank.account(A).balance() + "; B = " + bank.account(B).balance());
        rejected("5. Zero transaction", () -> bank.withdraw(A, Money.inr("0"), "Invalid amount"));
        rejected("6. Negative transaction", () -> bank.deposit(A, Money.inr("-1"), "Invalid amount"));
        bank.withdraw(A, Money.inr("11000"), "Withdraw to permitted overdraft boundary");
        System.out.println("7. Exact overdraft boundary accepted: A = " + bank.account(A).balance());
        rejected("8. Beyond overdraft", () -> bank.withdraw(A, Money.inr("0.01"), "Beyond boundary"));
        bank.freeze(A);
        rejected("9. Frozen account withdrawal", () -> bank.withdraw(A, Money.inr("1"), "Frozen debit"));
        bank.deposit(A, Money.inr("1000"), "Frozen account repayment permitted by policy");
        bank.unfreeze(A); bank.close(A);
        rejected("10. Closed account deposit", () -> bank.deposit(A, Money.inr("1"), "Closed credit"));
        rejected("11. Closed account reopening", () -> bank.unfreeze(A));
        System.out.println("\n12. Reloading persisted bank and reconciling every ledger:");
        report(new BankService(new AtomicFileBankRepository(path)));
        System.out.println("\nAll demonstration expectations passed. No real funds or UPI calls were used.");
    }
    private static void rejected(String label, Runnable command) {
        try { command.run(); throw new AssertionError("Expected rejection: " + label); }
        catch (BankingException expected) { System.out.println(label + " -> REJECTED: " + expected.getMessage()); }
    }
    private static void report(BankService bank) {
        var time = DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));
        bank.snapshot().accounts().values().stream().sorted(java.util.Comparator.comparing(a -> a.number().value())).forEach(account -> {
            account.reconcile();
            System.out.println("Account " + account.number() + " | " + account.status() + " | " + account.balance() + " | RECONCILED");
            for (LedgerEntry e : account.ledger()) System.out.printf("  %d | %s IST | %-6s | %s | balance %s | %s%n", e.sequence(), time.format(e.occurredAt()), e.direction(), e.amount(), e.balanceAfter(), e.reference());
        });
    }
}
