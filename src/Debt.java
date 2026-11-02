import java.math.BigDecimal;

public final class Debt {
    private final User debtor;
    private final User creditor;
    private final BigDecimal amount;

    Debt(User debtor, User creditor, BigDecimal amount) {
        this.debtor = debtor;
        this.creditor = creditor;
        this.amount = amount;
    }

    public User getDebtor() {
        return debtor;
    }

    public User getCreditor() {
        return creditor;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
