package com.paysmart.bank;

import java.util.Objects;

/** A number is a value; the account itself has a separate, immutable entity ID. */
public record AccountNumber(String value) {
    public AccountNumber {
        Objects.requireNonNull(value, "account number");
        if (!value.matches("[0-9]{10,18}")) throw new BankingException("Use a 10–18 digit account number");
    }
    @Override public String toString() { return value; }
}
