import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class Settlement {
    private final String id;
    private final String groupId;
    private final String payerId;
    private final String recipientId;
    private final BigDecimal amount;
    private final LocalDateTime createdAt;

    Settlement(String id, String groupId, String payerId, String recipientId, BigDecimal amount) {
        this(id, groupId, payerId, recipientId, amount, LocalDateTime.now());
    }

    Settlement(String id, String groupId, String payerId, String recipientId,
               BigDecimal amount, LocalDateTime createdAt) {
        this.id = id;
        this.groupId = groupId;
        this.payerId = payerId;
        this.recipientId = recipientId;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getPayerId() {
        return payerId;
    }

    public String getRecipientId() {
        return recipientId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "Settlement{id='" + id + "', payerId='" + payerId + "', recipientId='"
                + recipientId + "', amount=$" + amount.toPlainString() + "}";
    }
}
