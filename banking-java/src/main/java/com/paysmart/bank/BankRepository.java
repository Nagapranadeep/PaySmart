package com.paysmart.bank;
import java.util.function.Function;

/** Implementations serialize read–validate–commit; callers never persist half a transfer. */
public interface BankRepository {
    record Change<T>(BankSnapshot state, T result) { }
    BankSnapshot read();
    <T> T transact(Function<BankSnapshot, Change<T>> operation);
}
