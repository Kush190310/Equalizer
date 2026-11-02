import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class Group {
    private final String id;
    private final String name;
    private final Set<String> memberIds = new LinkedHashSet<>();

    Group(String id, String name, String creatorId) {
        this.id = requireNonBlank(id, "id");
        this.name = requireNonBlank(name, "name");
        addMember(creatorId);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<String> getMemberIds() {
        return Collections.unmodifiableList(new ArrayList<>(memberIds));
    }

    public boolean hasMember(String userId) {
        return memberIds.contains(userId);
    }

    void addMember(String userId) {
        memberIds.add(requireNonBlank(userId, "userId"));
    }

    void removeMember(String userId) {
        memberIds.remove(userId);
    }

    @Override
    public String toString() {
        return "Group{id='" + id + "', name='" + name + "', memberIds=" + memberIds + "}";
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
