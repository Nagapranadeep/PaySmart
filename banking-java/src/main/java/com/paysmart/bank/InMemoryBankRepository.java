package com.paysmart.bank;
import java.util.function.Function;

public final class InMemoryBankRepository implements BankRepository {
    private BankSnapshot state = BankSnapshot.empty();
    @Override public synchronized BankSnapshot read() { return state; }
    @Override public synchronized <T> T transact(Function<BankSnapshot, Change<T>> operation) {
        Change<T> change = operation.apply(state);
        BankSnapshot.validateTransition(state, change.state());
        state = change.state();
        return change.result();
    }
}
