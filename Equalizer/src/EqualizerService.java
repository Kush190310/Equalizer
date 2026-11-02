import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class EqualizerService {
    private static final int STORAGE_VERSION = 1;
    private static final int MAX_STORED_RECORDS = 100_000;

    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final List<Expense> expenses = new ArrayList<>();
    private final List<Settlement> settlements = new ArrayList<>();
    private final Path dataFile;
    private int nextUserId = 1;
    private int nextGroupId = 1;
    private int nextExpenseId = 1;
    private int nextSettlementId = 1;

    public EqualizerService() {
        this.dataFile = null;
    }

    public EqualizerService(Path dataFile) throws IOException {
        if (dataFile == null) {
            throw new IllegalArgumentException("Data file path must not be null.");
        }
        this.dataFile = dataFile.toAbsolutePath().normalize();
        load();
    }

    public User addUser(String name, String email) {
        String normalizedEmail = requireNonBlank(email, "email");
        for (User existing : users.values()) {
            if (existing.getEmail().equalsIgnoreCase(normalizedEmail)) {
                throw new IllegalArgumentException("A user with that email already exists.");
            }
        }
        User user = new User("U" + nextUserId, name, normalizedEmail);
        nextUserId++;
        users.put(user.getId(), user);
        persistOrRollback(() -> {
            users.remove(user.getId());
            nextUserId--;
        });
        return user;
    }

    public Group createGroup(String name, String creatorId) {
        requireUser(creatorId);
        Group group = new Group("G" + nextGroupId, name, creatorId);
        nextGroupId++;
        groups.put(group.getId(), group);
        persistOrRollback(() -> {
            groups.remove(group.getId());
            nextGroupId--;
        });
        return group;
    }

    public void addMember(String groupId, String userId) {
        Group group = requireGroup(groupId);
        requireUser(userId);
        if (group.hasMember(userId)) {
            return;
        }
        group.addMember(userId);
        persistOrRollback(() -> group.removeMember(userId));
    }

    public void deleteUser(String userId) {
        User user = requireUser(userId);
        for (Group group : groups.values()) {
            if (group.hasMember(userId)) {
                throw new IllegalStateException("Cannot remove a user who belongs to a group.");
            }
        }
        users.remove(userId);
        persistOrRollback(() -> users.put(userId, user));
    }

    public List<User> getUsers() {
        return Collections.unmodifiableList(new ArrayList<>(users.values()));
    }

    public List<Group> getGroups() {
        return Collections.unmodifiableList(new ArrayList<>(groups.values()));
    }

    public Group getGroup(String groupId) {
        return requireGroup(groupId);
    }

    public User getUser(String userId) {
        return requireUser(userId);
    }

    public User findUserByEmail(String email) {
        for (User user : users.values()) {
            if (user.getEmail().equalsIgnoreCase(email)) {
                return user;
            }
        }
        return null;
    }

    public List<Expense> getExpenses(String groupId) {
        requireGroup(groupId);
        List<Expense> groupExpenses = new ArrayList<>();
        for (Expense expense : expenses) {
            if (expense.getGroupId().equals(groupId)) {
                groupExpenses.add(expense);
            }
        }
        return Collections.unmodifiableList(groupExpenses);
    }

    public List<Settlement> getSettlements(String groupId) {
        requireGroup(groupId);
        List<Settlement> groupSettlements = new ArrayList<>();
        for (Settlement settlement : settlements) {
            if (settlement.getGroupId().equals(groupId)) {
                groupSettlements.add(settlement);
            }
        }
        return Collections.unmodifiableList(groupSettlements);
    }

    public Expense editExpense(String groupId, String expenseId, String description,
                               String payerId, BigDecimal amount, Map<String, BigDecimal> shares) {
        int index = findExpenseIndex(groupId, expenseId);
        Group group = requireGroup(groupId);
        requireMember(group, payerId);
        String normalizedDescription = requireNonBlank(description, "description");
        BigDecimal normalizedAmount = requirePositiveMoney(amount, "amount");
        Map<String, BigDecimal> normalizedShares = normalizeShares(group, normalizedAmount, shares);
        Expense previous = expenses.get(index);
        Expense replacement = new Expense(previous.getId(), groupId, normalizedDescription,
                payerId, normalizedAmount, normalizedShares, previous.getCreatedAt());
        expenses.set(index, replacement);
        persistOrRollback(() -> expenses.set(index, previous));
        return replacement;
    }

    public void deleteExpense(String groupId, String expenseId) {
        int index = findExpenseIndex(groupId, expenseId);
        Expense removed = expenses.remove(index);
        persistOrRollback(() -> expenses.add(index, removed));
    }

    public void deleteSettlement(String groupId, String settlementId) {
        int index = findSettlementIndex(groupId, settlementId);
        Settlement removed = settlements.remove(index);
        persistOrRollback(() -> settlements.add(index, removed));
    }

    public Settlement editSettlement(String groupId, String settlementId, String payerId,
                                     String recipientId, BigDecimal amount) {
        int index = findSettlementIndex(groupId, settlementId);
        Settlement previous = settlements.remove(index);
        try {
            Group group = requireGroup(groupId);
            requireMember(group, payerId);
            requireMember(group, recipientId);
            if (payerId.equals(recipientId)) {
                throw new IllegalArgumentException("A user cannot settle a debt with themselves.");
            }
            BigDecimal settlementAmount = requirePositiveMoney(amount, "amount");
            Map<User, BigDecimal> balances = getBalances(groupId);
            BigDecimal payerBalance = balances.get(requireUser(payerId));
            BigDecimal recipientBalance = balances.get(requireUser(recipientId));
            if (payerBalance.signum() >= 0 || recipientBalance.signum() <= 0) {
                throw new IllegalStateException("The selected users do not have a debt in that direction.");
            }
            if (settlementAmount.compareTo(payerBalance.abs().min(recipientBalance)) > 0) {
                throw new IllegalArgumentException("Settlement exceeds the outstanding debt.");
            }

            Settlement replacement = new Settlement(previous.getId(), groupId, payerId,
                    recipientId, settlementAmount, previous.getCreatedAt());
            settlements.add(index, replacement);
            persistOrRollback(() -> settlements.set(index, previous));
            return replacement;
        } catch (RuntimeException exception) {
            if (index < settlements.size() && settlements.get(index) == previous) {
                throw exception;
            }
            if (index < settlements.size()) {
                settlements.set(index, previous);
            } else {
                settlements.add(index, previous);
            }
            throw exception;
        }
    }

    public byte[] exportBackup() throws IOException {
        if (dataFile == null) {
            throw new IllegalStateException("Backups are only available when file persistence is enabled.");
        }
        save();
        return Files.readAllBytes(dataFile);
    }

    public void restoreBackup(byte[] backup) throws IOException {
        if (dataFile == null) {
            throw new IllegalStateException("Restore is only available when file persistence is enabled.");
        }
        if (backup == null || backup.length == 0 || backup.length > 10_000_000) {
            throw new IllegalArgumentException("The selected backup is empty or too large.");
        }
        Path parent = dataFile.getParent();
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, "equalizer-restore-", ".properties");
        try {
            Files.write(temporaryFile, backup);
            EqualizerService restored = new EqualizerService(temporaryFile);
            Map<String, User> previousUsers = new LinkedHashMap<>(users);
            Map<String, Group> previousGroups = new LinkedHashMap<>(groups);
            List<Expense> previousExpenses = new ArrayList<>(expenses);
            List<Settlement> previousSettlements = new ArrayList<>(settlements);
            int previousNextUserId = nextUserId;
            int previousNextGroupId = nextGroupId;
            int previousNextExpenseId = nextExpenseId;
            int previousNextSettlementId = nextSettlementId;

            users.clear();
            users.putAll(restored.users);
            groups.clear();
            groups.putAll(restored.groups);
            expenses.clear();
            expenses.addAll(restored.expenses);
            settlements.clear();
            settlements.addAll(restored.settlements);
            nextUserId = restored.nextUserId;
            nextGroupId = restored.nextGroupId;
            nextExpenseId = restored.nextExpenseId;
            nextSettlementId = restored.nextSettlementId;
            try {
                save();
            } catch (IOException exception) {
                users.clear();
                users.putAll(previousUsers);
                groups.clear();
                groups.putAll(previousGroups);
                expenses.clear();
                expenses.addAll(previousExpenses);
                settlements.clear();
                settlements.addAll(previousSettlements);
                nextUserId = previousNextUserId;
                nextGroupId = previousNextGroupId;
                nextExpenseId = previousNextExpenseId;
                nextSettlementId = previousNextSettlementId;
                throw exception;
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    public void clearAllData() {
        Map<String, User> previousUsers = new LinkedHashMap<>(users);
        Map<String, Group> previousGroups = new LinkedHashMap<>(groups);
        List<Expense> previousExpenses = new ArrayList<>(expenses);
        List<Settlement> previousSettlements = new ArrayList<>(settlements);
        int previousNextUserId = nextUserId;
        int previousNextGroupId = nextGroupId;
        int previousNextExpenseId = nextExpenseId;
        int previousNextSettlementId = nextSettlementId;

        users.clear();
        groups.clear();
        expenses.clear();
        settlements.clear();
        nextUserId = 1;
        nextGroupId = 1;
        nextExpenseId = 1;
        nextSettlementId = 1;

        persistOrRollback(() -> {
            users.putAll(previousUsers);
            groups.putAll(previousGroups);
            expenses.addAll(previousExpenses);
            settlements.addAll(previousSettlements);
            nextUserId = previousNextUserId;
            nextGroupId = previousNextGroupId;
            nextExpenseId = previousNextExpenseId;
            nextSettlementId = previousNextSettlementId;
        });
    }

    public Expense addEqualExpense(String groupId, String description, String payerId,
                                   BigDecimal amount, List<String> participantIds) {
        BigDecimal total = requirePositiveMoney(amount, "amount");
        if (participantIds == null || participantIds.isEmpty()) {
            throw new IllegalArgumentException("An expense must have at least one participant.");
        }

        if (participantIds.size() != new java.util.LinkedHashSet<>(participantIds).size()) {
            throw new IllegalArgumentException("Participant IDs must not be repeated.");
        }

        BigInteger[] split = total.movePointRight(2).toBigIntegerExact()
                .divideAndRemainder(BigInteger.valueOf(participantIds.size()));
        Map<String, BigDecimal> shares = new LinkedHashMap<>();
        for (int i = 0; i < participantIds.size(); i++) {
            BigInteger cents = split[0].add(i < split[1].intValue() ? BigInteger.ONE : BigInteger.ZERO);
            shares.put(participantIds.get(i), new BigDecimal(cents, 2));
        }
        return addExpense(groupId, description, payerId, total, shares);
    }

    public Expense addExactExpense(String groupId, String description, String payerId,
                                   BigDecimal amount, Map<String, BigDecimal> shares) {
        BigDecimal total = requirePositiveMoney(amount, "amount");
        if (shares == null || shares.isEmpty()) {
            throw new IllegalArgumentException("An expense must have at least one participant.");
        }

        Map<String, BigDecimal> normalizedShares = new LinkedHashMap<>();
        BigDecimal sharesTotal = BigDecimal.ZERO.setScale(2);
        for (Map.Entry<String, BigDecimal> entry : shares.entrySet()) {
            BigDecimal share = normalizeMoney(entry.getValue(), "share");
            if (share.signum() < 0) {
                throw new IllegalArgumentException("Shares cannot be negative.");
            }
            normalizedShares.put(entry.getKey(), share);
            sharesTotal = sharesTotal.add(share);
        }
        if (sharesTotal.compareTo(total) != 0) {
            throw new IllegalArgumentException("Exact shares must add up to the expense total.");
        }
        return addExpense(groupId, description, payerId, total, normalizedShares);
    }

    public Map<User, BigDecimal> getBalances(String groupId) {
        Group group = requireGroup(groupId);
        Map<String, BigDecimal> balancesById = new LinkedHashMap<>();
        for (String memberId : group.getMemberIds()) {
            balancesById.put(memberId, BigDecimal.ZERO.setScale(2));
        }

        for (Expense expense : expenses) {
            if (!expense.getGroupId().equals(groupId)) {
                continue;
            }
            addToBalance(balancesById, expense.getPayerId(), expense.getAmount());
            for (Map.Entry<String, BigDecimal> share : expense.getShares().entrySet()) {
                addToBalance(balancesById, share.getKey(), share.getValue().negate());
            }
        }
        for (Settlement settlement : settlements) {
            if (!settlement.getGroupId().equals(groupId)) {
                continue;
            }
            addToBalance(balancesById, settlement.getPayerId(), settlement.getAmount());
            addToBalance(balancesById, settlement.getRecipientId(), settlement.getAmount().negate());
        }

        Map<User, BigDecimal> balances = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : balancesById.entrySet()) {
            balances.put(requireUser(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(balances);
    }

    public List<Debt> getSuggestedSettlements(String groupId) {
        List<Map.Entry<User, BigDecimal>> debtors = new ArrayList<>();
        List<Map.Entry<User, BigDecimal>> creditors = new ArrayList<>();
        for (Map.Entry<User, BigDecimal> balance : getBalances(groupId).entrySet()) {
            if (balance.getValue().signum() < 0) {
                debtors.add(new AbstractMap.SimpleEntry<>(balance.getKey(), balance.getValue()));
            } else if (balance.getValue().signum() > 0) {
                creditors.add(new AbstractMap.SimpleEntry<>(balance.getKey(), balance.getValue()));
            }
        }
        Comparator<Map.Entry<User, BigDecimal>> byLargestBalance =
                (left, right) -> right.getValue().abs().compareTo(left.getValue().abs());
        debtors.sort(byLargestBalance);
        creditors.sort(byLargestBalance);

        List<Debt> debts = new ArrayList<>();
        int debtorIndex = 0;
        int creditorIndex = 0;
        while (debtorIndex < debtors.size() && creditorIndex < creditors.size()) {
            Map.Entry<User, BigDecimal> debtor = debtors.get(debtorIndex);
            Map.Entry<User, BigDecimal> creditor = creditors.get(creditorIndex);
            BigDecimal amount = debtor.getValue().abs().min(creditor.getValue());
            debts.add(new Debt(debtor.getKey(), creditor.getKey(), amount));
            debtor.setValue(debtor.getValue().add(amount));
            creditor.setValue(creditor.getValue().subtract(amount));
            if (debtor.getValue().signum() == 0) {
                debtorIndex++;
            }
            if (creditor.getValue().signum() == 0) {
                creditorIndex++;
            }
        }
        return Collections.unmodifiableList(debts);
    }

    public Settlement addSettlement(String groupId, String payerId, String recipientId,
                                    BigDecimal amount) {
        Group group = requireGroup(groupId);
        requireMember(group, payerId);
        requireMember(group, recipientId);
        if (payerId.equals(recipientId)) {
            throw new IllegalArgumentException("A user cannot settle a debt with themselves.");
        }
        BigDecimal settlementAmount = requirePositiveMoney(amount, "amount");
        Map<User, BigDecimal> balances = getBalances(groupId);
        BigDecimal payerBalance = balances.get(requireUser(payerId));
        BigDecimal recipientBalance = balances.get(requireUser(recipientId));
        if (payerBalance.signum() >= 0 || recipientBalance.signum() <= 0) {
            throw new IllegalStateException("The selected users do not have a debt in that direction.");
        }
        if (settlementAmount.compareTo(payerBalance.abs().min(recipientBalance)) > 0) {
            throw new IllegalArgumentException("Settlement exceeds the outstanding debt.");
        }

        Settlement settlement = new Settlement("S" + nextSettlementId, groupId,
                payerId, recipientId, settlementAmount);
        nextSettlementId++;
        settlements.add(settlement);
        persistOrRollback(() -> {
            settlements.remove(settlement);
            nextSettlementId--;
        });
        return settlement;
    }

    private void persistOrRollback(Runnable rollback) {
        if (dataFile == null) {
            return;
        }
        try {
            save();
        } catch (IOException exception) {
            rollback.run();
            throw new IllegalStateException("Could not save Equalizer data: "
                    + exception.getMessage(), exception);
        }
    }

    private void load() throws IOException {
        if (!Files.exists(dataFile)) {
            return;
        }
        Properties stored = new Properties();
        try (InputStream input = Files.newInputStream(dataFile)) {
            stored.load(input);
        }

        int version = readCount(stored, "format");
        if (version != STORAGE_VERSION) {
            throw new IOException("Unsupported Equalizer data format version: " + version);
        }

        int userCount = readCount(stored, "users.count");
        for (int i = 0; i < userCount; i++) {
            String prefix = "users." + i + ".";
            User user = new User(readEncoded(stored, prefix + "id"),
                    readEncoded(stored, prefix + "name"),
                    readEncoded(stored, prefix + "email"));
            if (users.containsKey(user.getId())) {
                throw new IOException("Duplicate user ID in Equalizer data: " + user.getId());
            }
            for (User existing : users.values()) {
                if (existing.getEmail().equalsIgnoreCase(user.getEmail())) {
                    throw new IOException("Duplicate user email in Equalizer data.");
                }
            }
            users.put(user.getId(), user);
        }
        nextUserId = userCount + 1;

        int groupCount = readCount(stored, "groups.count");
        for (int i = 0; i < groupCount; i++) {
            String prefix = "groups." + i + ".";
            String id = readEncoded(stored, prefix + "id");
            String name = readEncoded(stored, prefix + "name");
            int memberCount = readCount(stored, prefix + "members.count");
            if (memberCount == 0) {
                throw new IOException("A stored group cannot have zero members.");
            }
            List<String> memberIds = new ArrayList<>();
            for (int memberIndex = 0; memberIndex < memberCount; memberIndex++) {
                String memberId = readEncoded(stored, prefix + "members." + memberIndex);
                if (!users.containsKey(memberId)) {
                    throw new IOException("Unknown stored group member: " + memberId);
                }
                memberIds.add(memberId);
            }
            if (memberIds.size() != new java.util.LinkedHashSet<>(memberIds).size()) {
                throw new IOException("Duplicate member in stored group: " + id);
            }
            Group group = new Group(id, name, memberIds.get(0));
            for (int memberIndex = 1; memberIndex < memberIds.size(); memberIndex++) {
                group.addMember(memberIds.get(memberIndex));
            }
            if (groups.putIfAbsent(id, group) != null) {
                throw new IOException("Duplicate group ID in Equalizer data: " + id);
            }
        }
        nextGroupId = groupCount + 1;

        int expenseCount = readCount(stored, "expenses.count");
        for (int i = 0; i < expenseCount; i++) {
            String prefix = "expenses." + i + ".";
            String id = readEncoded(stored, prefix + "id");
            String groupId = readEncoded(stored, prefix + "groupId");
            String description = readEncoded(stored, prefix + "description");
            String payerId = readEncoded(stored, prefix + "payerId");
            BigDecimal amount = readStoredMoney(stored, prefix + "amount");
            LocalDateTime createdAt = readStoredDate(stored, prefix + "createdAt");
            Group group = groups.get(groupId);
            if (group == null || !group.hasMember(payerId)) {
                throw new IOException("Stored expense references a missing group or payer.");
            }
            int shareCount = readCount(stored, prefix + "shares.count");
            if (shareCount == 0) {
                throw new IOException("A stored expense must include participants.");
            }
            Map<String, BigDecimal> shares = new LinkedHashMap<>();
            BigDecimal shareTotal = BigDecimal.ZERO.setScale(2);
            for (int shareIndex = 0; shareIndex < shareCount; shareIndex++) {
                String sharePrefix = prefix + "shares." + shareIndex + ".";
                String userId = readEncoded(stored, sharePrefix + "userId");
                BigDecimal share = readStoredMoney(stored, sharePrefix + "amount");
                if (!group.hasMember(userId) || share.signum() < 0
                        || shares.putIfAbsent(userId, share) != null) {
                    throw new IOException("Invalid participant in stored expense: " + id);
                }
                shareTotal = shareTotal.add(share);
            }
            if (amount.signum() <= 0 || shareTotal.compareTo(amount) != 0) {
                throw new IOException("Invalid amount or shares in stored expense: " + id);
            }
            expenses.add(new Expense(id, groupId, description, payerId, amount, shares, createdAt));
        }
        nextExpenseId = expenseCount + 1;

        int settlementCount = readCount(stored, "settlements.count");
        for (int i = 0; i < settlementCount; i++) {
            String prefix = "settlements." + i + ".";
            String id = readEncoded(stored, prefix + "id");
            String groupId = readEncoded(stored, prefix + "groupId");
            String payerId = readEncoded(stored, prefix + "payerId");
            String recipientId = readEncoded(stored, prefix + "recipientId");
            BigDecimal amount = readStoredMoney(stored, prefix + "amount");
            LocalDateTime createdAt = readStoredDate(stored, prefix + "createdAt");
            Group group = groups.get(groupId);
            if (group == null || !group.hasMember(payerId) || !group.hasMember(recipientId)
                    || payerId.equals(recipientId) || amount.signum() <= 0) {
                throw new IOException("Invalid stored settlement: " + id);
            }
            settlements.add(new Settlement(id, groupId, payerId, recipientId, amount, createdAt));
        }
        nextSettlementId = settlementCount + 1;
    }

    private void save() throws IOException {
        Properties stored = new Properties();
        stored.setProperty("format", Integer.toString(STORAGE_VERSION));
        stored.setProperty("users.count", Integer.toString(users.size()));
        int index = 0;
        for (User user : users.values()) {
            String prefix = "users." + index++ + ".";
            writeEncoded(stored, prefix + "id", user.getId());
            writeEncoded(stored, prefix + "name", user.getName());
            writeEncoded(stored, prefix + "email", user.getEmail());
        }

        stored.setProperty("groups.count", Integer.toString(groups.size()));
        index = 0;
        for (Group group : groups.values()) {
            String prefix = "groups." + index++ + ".";
            writeEncoded(stored, prefix + "id", group.getId());
            writeEncoded(stored, prefix + "name", group.getName());
            stored.setProperty(prefix + "members.count", Integer.toString(group.getMemberIds().size()));
            for (int memberIndex = 0; memberIndex < group.getMemberIds().size(); memberIndex++) {
                writeEncoded(stored, prefix + "members." + memberIndex,
                        group.getMemberIds().get(memberIndex));
            }
        }

        stored.setProperty("expenses.count", Integer.toString(expenses.size()));
        for (int expenseIndex = 0; expenseIndex < expenses.size(); expenseIndex++) {
            Expense expense = expenses.get(expenseIndex);
            String prefix = "expenses." + expenseIndex + ".";
            writeEncoded(stored, prefix + "id", expense.getId());
            writeEncoded(stored, prefix + "groupId", expense.getGroupId());
            writeEncoded(stored, prefix + "description", expense.getDescription());
            writeEncoded(stored, prefix + "payerId", expense.getPayerId());
            writeEncoded(stored, prefix + "amount", expense.getAmount().toPlainString());
            writeEncoded(stored, prefix + "createdAt", expense.getCreatedAt().toString());
            stored.setProperty(prefix + "shares.count", Integer.toString(expense.getShares().size()));
            int shareIndex = 0;
            for (Map.Entry<String, BigDecimal> share : expense.getShares().entrySet()) {
                String sharePrefix = prefix + "shares." + shareIndex++ + ".";
                writeEncoded(stored, sharePrefix + "userId", share.getKey());
                writeEncoded(stored, sharePrefix + "amount", share.getValue().toPlainString());
            }
        }

        stored.setProperty("settlements.count", Integer.toString(settlements.size()));
        for (int settlementIndex = 0; settlementIndex < settlements.size(); settlementIndex++) {
            Settlement settlement = settlements.get(settlementIndex);
            String prefix = "settlements." + settlementIndex + ".";
            writeEncoded(stored, prefix + "id", settlement.getId());
            writeEncoded(stored, prefix + "groupId", settlement.getGroupId());
            writeEncoded(stored, prefix + "payerId", settlement.getPayerId());
            writeEncoded(stored, prefix + "recipientId", settlement.getRecipientId());
            writeEncoded(stored, prefix + "amount", settlement.getAmount().toPlainString());
            writeEncoded(stored, prefix + "createdAt", settlement.getCreatedAt().toString());
        }

        Path parent = dataFile.getParent();
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, dataFile.getFileName().toString(), ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                stored.store(output, "Equalizer local data");
            }
            try {
                Files.move(temporaryFile, dataFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static int readCount(Properties stored, String key) throws IOException {
        String value = stored.getProperty(key);
        try {
            int count = Integer.parseInt(value);
            if (count < 0 || count > MAX_STORED_RECORDS) {
                throw new IOException("Invalid record count for " + key + ".");
            }
            return count;
        } catch (NumberFormatException exception) {
            throw new IOException("Missing or invalid storage field: " + key + ".", exception);
        }
    }

    private static String readEncoded(Properties stored, String key) throws IOException {
        String value = stored.getProperty(key);
        if (value == null) {
            throw new IOException("Missing storage field: " + key + ".");
        }
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid encoded storage field: " + key + ".", exception);
        }
    }

    private static BigDecimal readStoredMoney(Properties stored, String key) throws IOException {
        String value = readEncoded(stored, key);
        try {
            return normalizeMoney(new BigDecimal(value), key);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid monetary value in stored field: " + key + ".", exception);
        }
    }

    private static LocalDateTime readStoredDate(Properties stored, String key) throws IOException {
        try {
            return LocalDateTime.parse(readEncoded(stored, key));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid date in stored field: " + key + ".", exception);
        }
    }

    private static void writeEncoded(Properties stored, String key, String value) {
        stored.setProperty(key, Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    private Expense addExpense(String groupId, String description, String payerId,
                              BigDecimal amount, Map<String, BigDecimal> shares) {
        Group group = requireGroup(groupId);
        requireMember(group, payerId);
        String normalizedDescription = requireNonBlank(description, "description");
        BigDecimal normalizedAmount = requirePositiveMoney(amount, "amount");
        Map<String, BigDecimal> normalizedShares = normalizeShares(group, normalizedAmount, shares);

        Expense expense = new Expense("E" + nextExpenseId, groupId, normalizedDescription,
                payerId, normalizedAmount, normalizedShares);
        nextExpenseId++;
        expenses.add(expense);
        persistOrRollback(() -> {
            expenses.remove(expense);
            nextExpenseId--;
        });
        return expense;
    }

    private Map<String, BigDecimal> normalizeShares(Group group, BigDecimal amount,
                                                    Map<String, BigDecimal> shares) {
        if (shares == null) {
            throw new IllegalArgumentException("Shares must not be null.");
        }
        if (shares.isEmpty()) {
            throw new IllegalArgumentException("An expense must have at least one participant.");
        }
        Map<String, BigDecimal> normalizedShares = new LinkedHashMap<>();
        BigDecimal sharesTotal = BigDecimal.ZERO.setScale(2);
        for (Map.Entry<String, BigDecimal> shareEntry : shares.entrySet()) {
            requireMember(group, shareEntry.getKey());
            BigDecimal share = normalizeMoney(shareEntry.getValue(), "share");
            if (share.signum() < 0) {
                throw new IllegalArgumentException("Shares cannot be negative.");
            }
            normalizedShares.put(shareEntry.getKey(), share);
            sharesTotal = sharesTotal.add(share);
        }
        if (sharesTotal.compareTo(amount) != 0) {
            throw new IllegalArgumentException("Shares must add up to the expense total.");
        }
        return normalizedShares;
    }

    private int findExpenseIndex(String groupId, String expenseId) {
        requireGroup(groupId);
        for (int i = 0; i < expenses.size(); i++) {
            Expense expense = expenses.get(i);
            if (expense.getGroupId().equals(groupId) && expense.getId().equals(expenseId)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown expense ID: " + expenseId);
    }

    private int findSettlementIndex(String groupId, String settlementId) {
        requireGroup(groupId);
        for (int i = 0; i < settlements.size(); i++) {
            Settlement settlement = settlements.get(i);
            if (settlement.getGroupId().equals(groupId) && settlement.getId().equals(settlementId)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown settlement ID: " + settlementId);
    }

    private void addToBalance(Map<String, BigDecimal> balances, String userId, BigDecimal amount) {
        balances.put(userId, balances.get(userId).add(amount));
    }

    private void requireMember(Group group, String userId) {
        requireUser(userId);
        if (!group.hasMember(userId)) {
            throw new IllegalArgumentException("User " + userId + " is not a member of group "
                    + group.getId() + ".");
        }
    }

    private User requireUser(String userId) {
        User user = users.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("Unknown user ID: " + userId);
        }
        return user;
    }

    private Group requireGroup(String groupId) {
        Group group = groups.get(groupId);
        if (group == null) {
            throw new IllegalArgumentException("Unknown group ID: " + groupId);
        }
        return group;
    }

    private static BigDecimal requirePositiveMoney(BigDecimal value, String fieldName) {
        BigDecimal normalized = normalizeMoney(value, fieldName);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero.");
        }
        return normalized;
    }

    private static BigDecimal normalizeMoney(BigDecimal value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null.");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(fieldName + " cannot have more than two decimal places.");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }
        return value.trim();
    }
}
