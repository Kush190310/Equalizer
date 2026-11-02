import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class AccountManager {
    private static final int ITERATIONS = 310_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;

    public record Account(String userId, String email, boolean admin) {}

    private final Path file;
    private final Properties accounts = new Properties();
    private final SecureRandom random = new SecureRandom();

    public AccountManager(Path file, EqualizerService service) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            try (var input = Files.newInputStream(file)) {
                accounts.load(input);
            }
            validateForService(accounts, service);
        }
    }

    public synchronized Account register(String name, String email, char[] password,
                                         EqualizerService service) throws IOException {
        try {
            String normalizedEmail = normalizeEmail(email);
            validatePassword(password);
            if (findByEmail(normalizedEmail) != null) {
                throw new IllegalArgumentException("An account with that email already exists.");
            }

            boolean admin = accounts.isEmpty();
            User user = service.addUser(name, normalizedEmail);
            byte[] salt = new byte[SALT_LENGTH];
            random.nextBytes(salt);
            accounts.setProperty(user.getId() + ".email", normalizedEmail);
            accounts.setProperty(user.getId() + ".salt", Base64.getEncoder().encodeToString(salt));
            accounts.setProperty(user.getId() + ".hash",
                    Base64.getEncoder().encodeToString(hash(password, salt)));
            accounts.setProperty(user.getId() + ".admin", Boolean.toString(admin));
            try {
                save();
            } catch (IOException e) {
                accounts.remove(user.getId() + ".email");
                accounts.remove(user.getId() + ".salt");
                accounts.remove(user.getId() + ".hash");
                accounts.remove(user.getId() + ".admin");
                service.deleteUser(user.getId());
                throw e;
            }
            return new Account(user.getId(), normalizedEmail, admin);
        } finally {
            if (password != null) java.util.Arrays.fill(password, '\0');
        }
    }

    public synchronized Account authenticate(String email, char[] password) {
        try {
            String normalizedEmail = normalizeEmail(email);
            Account account = findByEmail(normalizedEmail);
            if (account == null) {
                hash(password, new byte[SALT_LENGTH]);
                return null;
            }
            byte[] salt = Base64.getDecoder().decode(accounts.getProperty(account.userId() + ".salt"));
            byte[] expected = Base64.getDecoder().decode(accounts.getProperty(account.userId() + ".hash"));
            return java.security.MessageDigest.isEqual(expected, hash(password, salt)) ? account : null;
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    public synchronized Account getAccount(String userId) {
        String email = accounts.getProperty(userId + ".email");
        return email == null ? null : new Account(userId, email,
                Boolean.parseBoolean(accounts.getProperty(userId + ".admin", "false")));
    }

    public synchronized List<Account> getAccounts() {
        List<Account> result = new ArrayList<>();
        for (String key : accounts.stringPropertyNames()) {
            if (key.endsWith(".email")) {
                String userId = key.substring(0, key.length() - ".email".length());
                result.add(getAccount(userId));
            }
        }
        return List.copyOf(result);
    }

    public synchronized byte[] exportAccounts() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        accounts.store(output, "Equalizer account credentials");
        return output.toByteArray();
    }

    public synchronized void restoreAccounts(byte[] data, EqualizerService service) throws IOException {
        Properties restored = loadProperties(data);
        validateForService(restored, service);
        Properties previous = new Properties();
        previous.putAll(accounts);
        accounts.clear();
        accounts.putAll(restored);
        try {
            save();
        } catch (IOException e) {
            accounts.clear();
            accounts.putAll(previous);
            throw e;
        }
    }

    public static void validateBackupForService(byte[] data, EqualizerService service) throws IOException {
        validateForService(loadProperties(data), service);
    }

    public synchronized void clear() throws IOException {
        Properties previous = new Properties();
        previous.putAll(accounts);
        accounts.clear();
        try {
            save();
        } catch (IOException e) {
            accounts.putAll(previous);
            throw e;
        }
    }

    public synchronized boolean hasAccounts() {
        return !accounts.isEmpty();
    }

    private Account findByEmail(String email) {
        for (String key : accounts.stringPropertyNames()) {
            if (key.endsWith(".email") && email.equalsIgnoreCase(accounts.getProperty(key))) {
                return getAccount(key.substring(0, key.length() - ".email".length()));
            }
        }
        return null;
    }

    private void save() throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "accounts-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                accounts.store(output, "Equalizer accounts");
            }
            try {
                Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Properties loadProperties(byte[] data) throws IOException {
        Properties result = new Properties();
        result.load(new java.io.ByteArrayInputStream(data));
        return result;
    }

    private static void validateForService(Properties properties, EqualizerService service) {
        HashSet<String> userIds = new HashSet<>();
        for (String key : properties.stringPropertyNames()) {
            int separator = key.lastIndexOf('.');
            if (separator <= 0) {
                throw new IllegalArgumentException("Account data contains an invalid property.");
            }
            String field = key.substring(separator + 1);
            if (!List.of("email", "salt", "hash", "admin").contains(field)) {
                throw new IllegalArgumentException("Account data contains an unsupported property.");
            }
            userIds.add(key.substring(0, separator));
        }
        HashSet<String> emails = new HashSet<>();
        int administrators = 0;
        for (String userId : userIds) {
            String email = properties.getProperty(userId + ".email");
            String salt = properties.getProperty(userId + ".salt");
            String hash = properties.getProperty(userId + ".hash");
            String admin = properties.getProperty(userId + ".admin");
            if (email == null || salt == null || hash == null || admin == null) {
                throw new IllegalArgumentException("Account backup is missing credential data.");
            }
            String normalizedEmail = normalizeEmail(email);
            if (!normalizedEmail.equals(email) || !emails.add(normalizedEmail)) {
                throw new IllegalArgumentException("Account backup contains duplicate or invalid email addresses.");
            }
            if (Base64.getDecoder().decode(salt).length != SALT_LENGTH
                    || Base64.getDecoder().decode(hash).length != KEY_LENGTH / 8) {
                throw new IllegalArgumentException("Account backup contains invalid password-hash data.");
            }
            if (!"true".equals(admin) && !"false".equals(admin)) {
                throw new IllegalArgumentException("Account backup contains an invalid administrator flag.");
            }
            if ("true".equals(admin)) administrators++;
            service.getUser(userId);
        }
        if (!userIds.isEmpty() && administrators != 1) {
            throw new IllegalArgumentException("Account backup must contain exactly one administrator.");
        }
    }

    private static String normalizeEmail(String email) {
        if (email == null || !email.matches("(?i)^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$")) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length < 10 || password.length > 256) {
            throw new IllegalArgumentException("Password must contain 10 to 256 characters.");
        }
    }

    private static byte[] hash(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Password hashing is unavailable.", e);
        } finally {
            spec.clearPassword();
        }
    }
}
