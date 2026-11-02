import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class Main {
    private static final DateTimeFormatter ACTIVITY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final Scanner scanner = new Scanner(System.in);
    private final EqualizerService service = new EqualizerService();

    public static void main(String[] args) {
        if (args.length > 0 && "--console".equals(args[0])) {
            new Main().run();
            return;
        }
        try {
            java.nio.file.Path dataFile = java.nio.file.Path.of(
                    System.getProperty("equalizer.data", "data/equalizer-data.properties"))
                    .toAbsolutePath().normalize();
            EqualizerService service = new EqualizerService(dataFile);
            java.nio.file.Path dataDirectory = dataFile.getParent();
            java.nio.file.Path accountsFile = dataDirectory.resolve("accounts.properties");
            if (!java.nio.file.Files.exists(accountsFile) && !service.getUsers().isEmpty()) {
                java.nio.file.Files.createDirectories(dataDirectory);
                java.nio.file.Path legacyBackup = dataDirectory.resolve("legacy-before-accounts.properties");
                if (!java.nio.file.Files.exists(legacyBackup)) {
                    java.nio.file.Files.write(legacyBackup, service.exportBackup());
                }
                service.clearAllData();
                System.out.println("Existing data was preserved in " + legacyBackup
                        + "; the account-protected workspace starts fresh.");
            }
            AccountManager accounts = new AccountManager(accountsFile, service);
            new WebServer(service, accounts).start();
        } catch (java.io.IOException | IllegalArgumentException exception) {
            System.err.println("Could not start Equalizer: " + exception.getMessage());
        }
    }

    private void run() {
        System.out.println();
        System.out.println("========================================");
        System.out.println("              EQUALIZER");
        System.out.println("     Shared expenses, simplified");
        System.out.println("========================================");
        boolean running = true;
        while (running) {
            printMenu();
            try {
                String choice = prompt("Choose an option: ");
                switch (choice) {
                    case "1":
                        addUser();
                        break;
                    case "2":
                        createGroup();
                        break;
                    case "3":
                        addGroupMember();
                        break;
                    case "4":
                        listUsersAndGroups();
                        break;
                    case "5":
                        addExpense();
                        break;
                    case "6":
                        showBalances();
                        break;
                    case "7":
                        settleDebt();
                        break;
                    case "8":
                        showGroupActivity();
                        break;
                    case "0":
                        running = false;
                        break;
                    default:
                        System.out.println("Please choose a number from 0 to 8.");
                }
            } catch (EndOfInputException exception) {
                running = false;
            } catch (IllegalArgumentException | IllegalStateException exception) {
                System.out.println();
                System.out.println("  ! " + exception.getMessage());
            }
        }
        System.out.println();
        System.out.println("Thanks for using Equalizer.");
    }

    private void printMenu() {
        System.out.println();
        System.out.println("--- PEOPLE & GROUPS --------------------");
        System.out.println("  1  Register user       2  Create group");
        System.out.println("  3  Add group member    4  Browse users/groups");
        System.out.println("--- EXPENSES & BALANCES ----------------");
        System.out.println("  5  Add expense         6  View balances");
        System.out.println("  7  Record settlement   8  View group activity");
        System.out.println("----------------------------------------");
        System.out.println("0. Exit");
    }

    private void addUser() {
        section("Register a user");
        User user = service.addUser(prompt("Name: "), prompt("Email: "));
        success("Created " + user.getName() + " (" + user.getId() + ").");
    }

    private void createGroup() {
        section("Create a group");
        showUsers();
        String name = prompt("Group name: ");
        String creatorId = prompt("Creator's user ID: ");
        Group group = service.createGroup(name, creatorId);
        success("Created \"" + group.getName() + "\" (" + group.getId() + ").");
    }

    private void addGroupMember() {
        section("Add a group member");
        showGroups();
        String groupId = prompt("Group ID: ");
        showUsers();
        String userId = prompt("User ID to add: ");
        service.addMember(groupId, userId);
        success("Added " + service.getUser(userId).getName() + " to "
                + service.getGroup(groupId).getName() + ".");
    }

    private void listUsersAndGroups() {
        section("Users");
        if (service.getUsers().isEmpty()) {
            System.out.println("  No users yet. Register someone with option 1.");
        }
        for (User user : service.getUsers()) {
            System.out.println("  " + user.getId() + "  " + user.getName() + "  <"
                    + user.getEmail() + ">");
        }

        section("Groups");
        if (service.getGroups().isEmpty()) {
            System.out.println("  No groups yet. Create one with option 2.");
        }
        for (Group group : service.getGroups()) {
            printGroup(group);
        }
    }

    private void addExpense() {
        section("Add an expense");
        showGroups();
        String groupId = prompt("Group ID: ");
        Group group = service.getGroup(groupId);
        showMembers(group);
        String description = prompt("Description: ");
        String payerId = prompt("Payer's user ID: ");
        BigDecimal amount = readMoney("Total amount ($): ");

        int participantCount = readPositiveInteger("Number of participants: ");
        if (participantCount > group.getMemberIds().size()) {
            throw new IllegalArgumentException("There are only " + group.getMemberIds().size()
                    + " members in this group.");
        }
        List<String> participantIds = new ArrayList<>();
        for (int i = 0; i < participantCount; i++) {
            participantIds.add(prompt("Participant " + (i + 1) + " user ID: "));
        }

        System.out.println("Split method:  1) Equally   2) Exact shares");
        String splitChoice = prompt("Split type: ");
        Expense expense;
        if ("1".equals(splitChoice)) {
            expense = service.addEqualExpense(groupId, description, payerId, amount, participantIds);
            System.out.println("  Split equally among " + expense.getShares().size()
                    + " participants; any remaining cents were distributed.");
        } else if ("2".equals(splitChoice)) {
            Map<String, BigDecimal> shares = new LinkedHashMap<>();
            for (String participantId : participantIds) {
                if (shares.containsKey(participantId)) {
                    throw new IllegalArgumentException("Participant IDs must not be repeated.");
                }
                shares.put(participantId, readMoney("Share for "
                        + service.getUser(participantId).getName() + " ($): "));
            }
            expense = service.addExactExpense(groupId, description, payerId, amount, shares);
        } else {
            throw new IllegalArgumentException("Split type must be 1 or 2.");
        }
        success("Recorded \"" + expense.getDescription() + "\" for "
                + formatMoney(expense.getAmount()) + ".");
    }

    private void showBalances() {
        section("Group balances");
        showGroups();
        String groupId = prompt("Group ID: ");
        Group group = service.getGroup(groupId);
        Map<User, BigDecimal> balances = service.getBalances(groupId);
        System.out.println();
        System.out.println("  " + group.getName() + "  |  " + balances.size() + " members");
        System.out.println("  --------------------------------------");
        for (Map.Entry<User, BigDecimal> entry : balances.entrySet()) {
            String position = entry.getValue().signum() > 0 ? "gets back"
                    : entry.getValue().signum() < 0 ? "owes" : "settled";
            System.out.printf("  %-20s %-10s %s%n", entry.getKey().getName(), position,
                    formatMoney(entry.getValue().abs()));
        }

        List<Debt> debts = service.getSuggestedSettlements(groupId);
        if (debts.isEmpty()) {
            System.out.println();
            System.out.println("  All settled. No payments needed.");
        } else {
            System.out.println();
            System.out.println("  Suggested payments (fewest transfers):");
            for (Debt debt : debts) {
                System.out.println("  " + debt.getDebtor().getName() + "  ->  "
                        + debt.getCreditor().getName() + "  " + formatMoney(debt.getAmount()));
            }
        }
    }

    private void settleDebt() {
        section("Record a settlement");
        showGroups();
        String groupId = prompt("Group ID: ");
        System.out.println("Use the suggested payments shown in option 6 to choose the right direction.");
        String payerId = prompt("Debtor's user ID: ");
        String recipientId = prompt("Creditor's user ID: ");
        BigDecimal amount = readMoney("Amount paid ($): ");
        Settlement settlement = service.addSettlement(groupId, payerId, recipientId, amount);
        success("Recorded payment of " + formatMoney(settlement.getAmount()) + " from "
                + service.getUser(payerId).getName() + " to "
                + service.getUser(recipientId).getName() + ".");
    }

    private void showGroupActivity() {
        section("Group activity");
        showGroups();
        String groupId = prompt("Group ID: ");
        List<Expense> expenses = service.getExpenses(groupId);
        List<Settlement> settlements = service.getSettlements(groupId);

        section("Expenses");
        if (expenses.isEmpty()) {
            System.out.println("  No expenses recorded.");
        }
        for (Expense expense : expenses) {
            System.out.println("  " + ACTIVITY_TIME.format(expense.getCreatedAt()) + "  "
                    + expense.getDescription() + "  " + formatMoney(expense.getAmount())
                    + "  paid by " + service.getUser(expense.getPayerId()).getName());
            for (Map.Entry<String, BigDecimal> share : expense.getShares().entrySet()) {
                System.out.println("    - " + service.getUser(share.getKey()).getName()
                        + ": " + formatMoney(share.getValue()));
            }
        }

        section("Settlements");
        if (settlements.isEmpty()) {
            System.out.println("  No settlements recorded.");
        }
        for (Settlement settlement : settlements) {
            System.out.println("  " + ACTIVITY_TIME.format(settlement.getCreatedAt()) + "  "
                    + service.getUser(settlement.getPayerId()).getName() + " paid "
                    + service.getUser(settlement.getRecipientId()).getName() + "  "
                    + formatMoney(settlement.getAmount()));
        }
    }

    private BigDecimal readMoney(String label) {
        while (true) {
            String value = prompt(label);
            try {
                BigDecimal amount = new BigDecimal(value);
                if (amount.signum() <= 0) {
                    System.out.println("  Enter an amount greater than zero.");
                } else if (amount.stripTrailingZeros().scale() > 2) {
                    System.out.println("  Enter no more than two digits after the decimal point.");
                } else {
                    return amount;
                }
            } catch (NumberFormatException exception) {
                System.out.println("  Enter a valid amount, for example 12.50.");
            }
        }
    }

    private int readPositiveInteger(String label) {
        while (true) {
            String value = prompt(label);
            try {
                int result = Integer.parseInt(value);
                if (result > 0) {
                    return result;
                }
                System.out.println("  Enter a whole number greater than zero.");
            } catch (NumberFormatException exception) {
                System.out.println("  Enter a valid whole number.");
            }
        }
    }

    private void showUsers() {
        System.out.println("Available users:");
        if (service.getUsers().isEmpty()) {
            System.out.println("  (none — register users first with option 1)");
            return;
        }
        for (User user : service.getUsers()) {
            System.out.println("  " + user.getId() + "  " + user.getName());
        }
    }

    private void showGroups() {
        System.out.println("Available groups:");
        if (service.getGroups().isEmpty()) {
            System.out.println("  (none — create a group first with option 2)");
            return;
        }
        for (Group group : service.getGroups()) {
            printGroup(group);
        }
    }

    private void showMembers(Group group) {
        System.out.println("Group members:");
        for (String memberId : group.getMemberIds()) {
            User user = service.getUser(memberId);
            System.out.println("  " + user.getId() + "  " + user.getName());
        }
    }

    private void printGroup(Group group) {
        List<String> memberNames = new ArrayList<>();
        for (String memberId : group.getMemberIds()) {
            memberNames.add(service.getUser(memberId).getName());
        }
        System.out.println("  " + group.getId() + "  " + group.getName()
                + "  (" + memberNames.size() + " members: " + String.join(", ", memberNames) + ")");
    }

    private void section(String title) {
        System.out.println();
        System.out.println(">>> " + title);
    }

    private void success(String message) {
        System.out.println();
        System.out.println("  + " + message);
    }

    private String formatMoney(BigDecimal amount) {
        return "$" + amount.setScale(2).toPlainString();
    }

    private String prompt(String label) {
        System.out.print(label);
        if (!scanner.hasNextLine()) {
            throw new EndOfInputException();
        }
        return scanner.nextLine().trim();
    }

    private static final class EndOfInputException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
