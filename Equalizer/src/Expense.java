import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Expense {
    private final String id;
    private final String groupId;
    private final String description;
    private final String payerId;
    private final BigDecimal amount;
    private final Map<String, BigDecimal> shares;
    private final LocalDateTime createdAt;

    Expense(String id, String groupId, String description, String payerId,
            BigDecimal amount, Map<String, BigDecimal> shares) {
        this(id, groupId, description, payerId, amount, shares, LocalDateTime.now());
    }

    Expense(String id, String groupId, String description, String payerId,
            BigDecimal amount, Map<String, BigDecimal> shares, LocalDateTime createdAt) {
        this.id = id;
        this.groupId = groupId;
        this.description = description;
        this.payerId = payerId;
        this.amount = amount;
        this.shares = Collections.unmodifiableMap(new LinkedHashMap<>(shares));
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getDescription() {
        return description;
    }

    public String getPayerId() {
        return payerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Map<String, BigDecimal> getShares() {
        return shares;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "Expense{id='" + id + "', description='" + description + "', amount=$"
                + amount.toPlainString() + ", payerId='" + payerId + "'}";
    }
}
