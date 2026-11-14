package com.paysmart.bank;
import java.nio.file.Path;

/** Child process exits without finally blocks, mimicking loss of the process after debit staging. */
public final class CrashDuringTransfer {
    public static void main(String[] args) {
        var bank = new BankService(new AtomicFileBankRepository(Path.of(args[0])), () -> Runtime.getRuntime().halt(99));
        bank.transfer(new AccountNumber("1000000001"), new AccountNumber("1000000002"), Money.inr("5000"), "process-crash-transfer");
        throw new AssertionError("The injected crash did not happen");
    }
}
