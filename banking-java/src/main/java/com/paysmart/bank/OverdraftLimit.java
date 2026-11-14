package com.paysmart.bank;

import java.util.Objects;

public record OverdraftLimit(Money amount) {
    public OverdraftLimit { Objects.requireNonNull(amount, "overdraft limit"); amount.requireNonNegative(); }
    public boolean permits(Money balance) { return balance.compareTo(amount.negate()) >= 0; }
}
