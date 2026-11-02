import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EqualizerPersistenceTest {
    public static void main(String[] args) throws Exception {
        Path dataFile = Files.createTempFile("equalizer-test-", ".properties");
        try {
            Files.delete(dataFile);
            EqualizerService service = new EqualizerService(dataFile);
            User alice = service.addUser("Alice", "alice@example.test");
            User bob = service.addUser("Bob", "bob@example.test");
            User casey = service.addUser("Casey", "casey@example.test");
            Group group = service.createGroup("Weekend", alice.getId());
            service.addMember(group.getId(), bob.getId());
            service.addMember(group.getId(), bob.getId());
            require(service.getGroup(group.getId()).getMemberIds().size() == 2,
                    "Adding the same group member twice should not duplicate them.");
            service.addMember(group.getId(), casey.getId());

            Expense equalExpense = service.addEqualExpense(group.getId(), "Taxi", alice.getId(),
                    new BigDecimal("10.00"), List.of(alice.getId(), bob.getId(), casey.getId()));
            require(equalExpense.getShares().get(alice.getId()).compareTo(new BigDecimal("3.34")) == 0,
                    "Equal split did not distribute the remaining cent.");
            require(equalExpense.getShares().get(bob.getId()).compareTo(new BigDecimal("3.33")) == 0,
                    "Equal split produced an incorrect share.");
            expectFailure(() -> service.addEqualExpense(group.getId(), "Duplicate", alice.getId(),
                    BigDecimal.ONE, List.of(alice.getId(), alice.getId())),
                    "Duplicate participants should be rejected.");

            Map<String, BigDecimal> shares = new LinkedHashMap<>();
            shares.put(alice.getId(), new BigDecimal("8.00"));
            shares.put(bob.getId(), new BigDecimal("4.00"));
            Expense expense = service.addExactExpense(group.getId(), "Dinner", bob.getId(),
                    new BigDecimal("12.00"), shares);
            expectFailure(() -> service.addExactExpense(group.getId(), "Invalid", alice.getId(),
                    new BigDecimal("12.00"), Map.of(alice.getId(), new BigDecimal("11.00"))),
                    "Exact shares that do not add up should be rejected.");
            Map<User, BigDecimal> balances = service.getBalances(group.getId());
            BigDecimal balanceTotal = balances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            require(balanceTotal.compareTo(BigDecimal.ZERO) == 0,
                    "Group balances should sum to zero.");
            List<Debt> suggested = service.getSuggestedSettlements(group.getId());
            require(!suggested.isEmpty(), "Expected suggested settlements.");
            Debt firstDebt = suggested.get(0);
            Settlement settlement = service.addSettlement(group.getId(), firstDebt.getDebtor().getId(),
                    firstDebt.getCreditor().getId(), new BigDecimal("1.00"));
            expectFailure(() -> service.addSettlement(group.getId(), alice.getId(), bob.getId(),
                    new BigDecimal("1000.00")),
                    "Settlement that exceeds outstanding debt should be rejected.");
            Settlement editedSettlement = service.editSettlement(group.getId(), settlement.getId(),
                    firstDebt.getDebtor().getId(), firstDebt.getCreditor().getId(),
                    new BigDecimal("0.50"));
            require(editedSettlement.getAmount().compareTo(new BigDecimal("0.50")) == 0,
                    "Settlement edits should update the amount.");
            expectFailure(() -> service.editSettlement(group.getId(), settlement.getId(),
                    firstDebt.getDebtor().getId(), firstDebt.getCreditor().getId(),
                    new BigDecimal("1000.00")),
                    "Invalid settlement edits should be rejected.");
            require(service.getSettlements(group.getId()).get(0).getAmount()
                            .compareTo(new BigDecimal("0.50")) == 0,
                    "A rejected settlement edit must leave the previous record intact.");

            Map<String, BigDecimal> editedShares = new LinkedHashMap<>();
            editedShares.put(alice.getId(), new BigDecimal("7.00"));
            editedShares.put(bob.getId(), new BigDecimal("5.00"));
            Expense edited = service.editExpense(group.getId(), expense.getId(), "Updated dinner",
                    alice.getId(), new BigDecimal("12.00"), editedShares);
            require("Updated dinner".equals(edited.getDescription()),
                    "Expense edits should update the description.");
            require(edited.getPayerId().equals(alice.getId()),
                    "Expense edits should update the payer.");
            byte[] backup = service.exportBackup();
            service.deleteSettlement(group.getId(), settlement.getId());
            service.deleteExpense(group.getId(), equalExpense.getId());
            require(service.getExpenses(group.getId()).size() == 1,
                    "Deleting an expense should remove it from the ledger.");
            require(service.getSettlements(group.getId()).isEmpty(),
                    "Deleting a settlement should remove it from the ledger.");
            service.restoreBackup(backup);
            require(service.getExpenses(group.getId()).size() == 2,
                    "Restoring a valid backup should replace the ledger.");
            require(service.getSettlements(group.getId()).size() == 1,
                    "Restoring a valid backup should restore settlements.");
            byte[] beforeInvalidRestore = service.exportBackup();
            expectFailure(() -> {
                try {
                    service.restoreBackup("not a properties backup".getBytes(
                            java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }, "Invalid backups should be rejected.");
            require(java.util.Arrays.equals(beforeInvalidRestore, service.exportBackup()),
                    "An invalid backup must not change saved data.");

            EqualizerService restored = new EqualizerService(dataFile);
            require(restored.getUsers().size() == 3, "Users were not restored.");
            require(restored.getGroups().size() == 1, "Group was not restored.");
            require(restored.getGroup(group.getId()).getMemberIds().size() == 3,
                    "Group members were not restored.");
            require(restored.getExpenses(group.getId()).size() == 2,
                    "Expense history was not restored.");
            require(restored.getExpenses(group.getId()).get(1).getId().equals(expense.getId()),
                    "Expense ID was not restored.");
            require(restored.getSettlements(group.getId()).size() == 1,
                    "Settlement history was not restored.");
            List<Debt> debts = restored.getSuggestedSettlements(group.getId());
            require(!debts.isEmpty(), "Remaining debt was not restored.");
            require(restored.getSettlements(group.getId()).get(0).getId().equals(settlement.getId()),
                    "Settlement ID was not restored.");

            Path accountsFile = dataFile.resolveSibling("equalizer-test-accounts.properties");
            try {
                AccountManager accounts = new AccountManager(accountsFile, restored);
                AccountManager.Account taylorAccount = accounts.register("Taylor", "taylor@example.test",
                        "long-test-password".toCharArray(), restored);
                require(taylorAccount.admin(), "The first account should be the administrator.");
                require(!taylorAccount.userId().equals(alice.getId()),
                        "A new account must not claim an existing ledger person.");
                require(accounts.authenticate("TAYLOR@example.test", "long-test-password".toCharArray()) != null,
                        "A registered account should authenticate case-insensitively.");
                require(accounts.authenticate("taylor@example.test", "wrong-password".toCharArray()) == null,
                        "An incorrect password must not authenticate.");
                expectFailure(() -> {
                    try {
                        accounts.register("Duplicate", "taylor@example.test",
                                "another-long-password".toCharArray(), restored);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                }, "Duplicate account emails should be rejected.");
                expectFailure(() -> {
                    try {
                        accounts.register("Claim", "alice@example.test",
                                "another-long-password".toCharArray(), restored);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                }, "An account must not claim an existing ledger identity.");
                AccountManager.validateBackupForService(accounts.exportAccounts(), restored);
                Files.deleteIfExists(accountsFile);
            } finally {
                Files.deleteIfExists(accountsFile);
            }

            User nextUser = restored.addUser("Jordan", "jordan@example.test");
            require("U5".equals(nextUser.getId()), "User IDs did not continue after account registration.");
            restored.clearAllData();
            EqualizerService cleared = new EqualizerService(dataFile);
            require(cleared.getUsers().isEmpty(), "Users remained after clearing all data.");
            require(cleared.getGroups().isEmpty(), "Groups remained after clearing all data.");
            System.out.println("Persistence test passed.");
        } finally {
            Files.deleteIfExists(dataFile);
            Files.deleteIfExists(dataFile.resolveSibling("equalizer-test-accounts.properties"));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectFailure(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
