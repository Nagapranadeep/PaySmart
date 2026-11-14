package com.paysmart.bank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/** Immutable value object. Signed balances are valid; operation amounts must be positive. */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        int scale = currency.getDefaultFractionDigits();
        if (scale < 0) throw new BankingException("Unsupported currency");
        try { amount = amount.setScale(scale, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException e) { throw new BankingException("Amount has excessive decimal precision"); }
    }
    public static Money inr(String amount) { return new Money(new BigDecimal(amount), Currency.getInstance("INR")); }
    public static Money zero(Currency currency) { return new Money(BigDecimal.ZERO, currency); }
    public Money add(Money other) { sameCurrency(other); return new Money(amount.add(other.amount), currency); }
    public Money subtract(Money other) { sameCurrency(other); return new Money(amount.subtract(other.amount), currency); }
    public Money negate() { return new Money(amount.negate(), currency); }
    public boolean isZero() { return amount.signum() == 0; }
    public void requirePositive() { if (amount.signum() <= 0) throw new BankingException("Transaction amount must be greater than zero"); }
    public void requireNonNegative() { if (amount.signum() < 0) throw new BankingException("Overdraft limit cannot be negative"); }
    public void sameCurrency(Money other) {
        Objects.requireNonNull(other, "money");
        if (!currency.equals(other.currency)) throw new BankingException("Currency mismatch");
    }
    @Override public int compareTo(Money other) { sameCurrency(other); return amount.compareTo(other.amount); }
    @Override public String toString() { return currency.getCurrencyCode() + " " + amount.toPlainString(); }
}
