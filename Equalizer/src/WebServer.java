import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class WebServer {
    private static final int PORT = 8443;
    private static final long SESSION_SECONDS = 12 * 60 * 60;
    private static final int MAX_BACKUP_SIZE = 10_000_000;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final EqualizerService service;
    private final AccountManager accounts;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private HttpsServer server;

    public WebServer(EqualizerService service, AccountManager accounts) {
        this.service = service;
        this.accounts = accounts;
    }

    public void start() throws IOException {
        SSLContext sslContext = createSslContext(
                Path.of(System.getProperty("equalizer.tls", "data/tls")));
        server = HttpsServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "equalizer-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();

        System.out.println("Equalizer is running securely at https://localhost:" + PORT);
        System.out.println("For shared Wi-Fi access, trust data/tls/equalizer-local-cert.cer "
                + "on each device, then open:");
        for (String address : lanAddresses()) {
            System.out.println("  https://" + address + ":" + PORT);
        }
        System.out.println("Keep this terminal open while using the app. Press Ctrl+C to stop.");
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(URI.create("https://localhost:" + PORT));
            } catch (IOException exception) {
                System.out.println("The browser could not be opened automatically.");
            }
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/".equals(path)) {
                send(exchange, 200, "text/html; charset=utf-8", readIndex());
                return;
            }
            if ("GET".equals(method) && "/api/session".equals(path)) {
                Session session = session(exchange);
                if (session == null) {
                    send(exchange, 200, "application/json; charset=utf-8",
                            "{\"authenticated\":false,\"registrationOpen\":"
                                    + !accounts.hasAccounts() + "}");
                } else {
                    AccountManager.Account account = accounts.getAccount(session.userId);
                    send(exchange, 200, "application/json; charset=utf-8",
                            "{\"authenticated\":true,\"userId\":" + quote(session.userId)
                                    + ",\"name\":" + quote(service.getUser(session.userId).getName())
                                    + ",\"admin\":" + account.admin() + "}");
                }
                return;
            }
            if ("POST".equals(method) && isAuthPath(path)) {
                requireSameOrigin(exchange);
                handleAuth(exchange, path, readForm(exchange));
                return;
            }
            Session session = session(exchange);
            if (session == null) {
                sendJsonError(exchange, 401, "Please sign in to continue.");
                return;
            }
            if ("GET".equals(method) && "/api/state".equals(path)) {
                send(exchange, 200, "application/json; charset=utf-8", stateJson(session));
            } else if ("GET".equals(method) && "/api/backup".equals(path)) {
                requireAdmin(session);
                sendBytes(exchange, 200, "application/zip", backupZip(),
                        "attachment; filename=\"equalizer-backup.zip\"");
            } else if ("POST".equals(method)) {
                requireSameOrigin(exchange);
                if ("/api/backup/restore".equals(path)) {
                    requireAdmin(session);
                    restoreZip(readLimited(exchange, MAX_BACKUP_SIZE));
                    sessions.clear();
                    clearCookie(exchange);
                    send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true}");
                } else {
                    handlePost(exchange, path, readForm(exchange), session);
                }
            } else {
                sendJsonError(exchange, 404, "Not found.");
            }
        } catch (AuthenticationException exception) {
            sendJsonError(exchange, 401, exception.getMessage());
        } catch (ForbiddenException exception) {
            sendJsonError(exchange, 403, exception.getMessage());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sendJsonError(exchange, 400, exception.getMessage());
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            sendJsonError(exchange, 500, "An unexpected server error occurred.");
        } finally {
            exchange.close();
        }
    }

    private void handleAuth(HttpExchange exchange, String path, Map<String, String> form)
            throws IOException {
        if ("/api/auth/logout".equals(path)) {
            Session current = session(exchange);
            if (current != null) {
                sessions.remove(current.token);
            }
            clearCookie(exchange);
            send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true}");
            return;
        }
        AccountManager.Account account;
        if ("/api/auth/register".equals(path)) {
            account = accounts.register(required(form, "name"), required(form, "email"),
                    required(form, "password").toCharArray(), service);
        } else if ("/api/auth/login".equals(path)) {
            account = accounts.authenticate(required(form, "email"),
                    required(form, "password").toCharArray());
            if (account == null) {
                throw new AuthenticationException("Email or password is incorrect.");
            }
        } else {
            sendJsonError(exchange, 404, "Not found.");
            return;
        }
        Session session = createSession(account.userId());
        setCookie(exchange, session.token);
        send(exchange, 200, "application/json; charset=utf-8",
                "{\"authenticated\":true,\"name\":" + quote(service.getUser(account.userId()).getName())
                        + ",\"admin\":" + account.admin() + "}");
    }

    private void handlePost(HttpExchange exchange, String path, Map<String, String> form, Session session)
            throws IOException {
        switch (path) {
            case "/api/groups": {
                Group group = service.createGroup(required(form, "name"), session.userId);
                send(exchange, 201, "application/json; charset=utf-8",
                        "{\"id\":" + quote(group.getId()) + ",\"name\":" + quote(group.getName()) + "}");
                return;
            }
            case "/api/groups/members": {
                String groupId = required(form, "groupId");
                requireGroupAccess(session, groupId);
                service.addMember(groupId, required(form, "userId"));
                send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true}");
                return;
            }
            case "/api/expenses": {
                String groupId = required(form, "groupId");
                requireGroupAccess(session, groupId);
                Expense expense = createExpense(form);
                send(exchange, 201, "application/json; charset=utf-8",
                        "{\"id\":" + quote(expense.getId()) + "}");
                return;
            }
            case "/api/expenses/edit": {
                String groupId = required(form, "groupId");
                requireGroupAccess(session, groupId);
                Expense parsed = createExpenseForEdit(form);
                Expense expense = service.editExpense(groupId, required(form, "expenseId"),
                        required(form, "description"), required(form, "payerId"),
                        new BigDecimal(required(form, "amount")), parsed.getShares());
                send(exchange, 200, "application/json; charset=utf-8",
                        "{\"id\":" + quote(expense.getId()) + "}");
                return;
            }
            case "/api/expenses/delete": {
                String groupId = required(form, "groupId");
                requireGroupAccess(session, groupId);
                service.deleteExpense(groupId, required(form, "expenseId"));
                send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true}");
                return;
            }
            case "/api/settlements": {
                String groupId = required(form, "groupId");
                requireGroupAccess(session, groupId);
                Settlement settlement = service.addSettlement(groupId,
                        required(form, "payerId"), required(form, "recipientId"),
                        new BigDecimal(required(form, "amount")));
                send(exchange, 201, "application/json; charset=utf-8",
                        "{\"id\":" + quote(settlement.getId()) + "}");
                return;
            }
            case "/api/settlements/edit": {
                String groupId = required(form, "groupId");
                requireGroupAccess(session, groupId);
                Settlement settlement = service.editSettlement(groupId,
                        required(form, "settlementId"), required(form, "payerId"),
                        required(form, "recipientId"), new BigDecimal(required(form, "amount")));
                send(exchange, 200, "application/json; charset=utf-8",
                        "{\"id\":" + quote(settlement.getId()) + "}");
                return;
            }
            case "/api/settlements/delete": {
                String groupId = required(form, "groupId");
                requireGroupAccess(session, groupId);
                service.deleteSettlement(groupId, required(form, "settlementId"));
                send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true}");
                return;
            }
            case "/api/reset": {
                requireAdmin(session);
                byte[] oldData = service.exportBackup();
                byte[] oldAccounts = accounts.exportAccounts();
                try {
                    service.clearAllData();
                    accounts.clear();
                    sessions.clear();
                    clearCookie(exchange);
                    send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true}");
                } catch (Exception failure) {
                    service.restoreBackup(oldData);
                    accounts.restoreAccounts(oldAccounts, service);
                    throw failure;
                }
                return;
            }
            default:
                sendJsonError(exchange, 404, "Not found.");
        }
    }

    private Expense createExpense(Map<String, String> form) {
        String groupId = required(form, "groupId");
        String description = required(form, "description");
        String payerId = required(form, "payerId");
        BigDecimal amount = new BigDecimal(required(form, "amount"));
        List<String> participantIds = participantIds(form);
        if ("equal".equals(required(form, "split"))) {
            return service.addEqualExpense(groupId, description, payerId, amount, participantIds);
        }
        if ("exact".equals(required(form, "split"))) {
            return service.addExactExpense(groupId, description, payerId, amount, shares(form, participantIds));
        }
        throw new IllegalArgumentException("Choose an equal or exact split.");
    }

    private Expense createExpenseForEdit(Map<String, String> form) {
        List<String> participantIds = participantIds(form);
        Map<String, BigDecimal> shares;
        if ("equal".equals(required(form, "split"))) {
            BigDecimal amount = new BigDecimal(required(form, "amount"));
            shares = equalShares(participantIds, amount);
        } else if ("exact".equals(required(form, "split"))) {
            shares = shares(form, participantIds);
        } else {
            throw new IllegalArgumentException("Choose an equal or exact split.");
        }
        String groupId = required(form, "groupId");
        return new Expense("edit", groupId, required(form, "description"), required(form, "payerId"),
                new BigDecimal(required(form, "amount")), shares);
    }

    private Map<String, BigDecimal> equalShares(List<String> participants, BigDecimal amount) {
        if (participants.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one participant.");
        }
        java.math.BigInteger[] division = amount.setScale(2).movePointRight(2).toBigIntegerExact()
                .divideAndRemainder(java.math.BigInteger.valueOf(participants.size()));
        BigDecimal share = new BigDecimal(division[0]).movePointLeft(2);
        int extraCents = division[1].intValueExact();
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (int i = 0; i < participants.size(); i++) {
            result.put(participants.get(i), i < extraCents
                    ? share.add(new BigDecimal("0.01")) : share);
        }
        return result;
    }

    private static Map<String, BigDecimal> shares(Map<String, String> form, List<String> participants) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (String userId : participants) {
            result.put(userId, new BigDecimal(required(form, "share_" + userId)));
        }
        return result;
    }

    private static List<String> participantIds(Map<String, String> form) {
        List<String> participantIds = new ArrayList<>();
        for (String id : required(form, "participants").split(",")) {
            participantIds.add(id);
        }
        if (participantIds.size() != new LinkedHashSet<>(participantIds).size()) {
            throw new IllegalArgumentException("Participant IDs must not be repeated.");
        }
        return participantIds;
    }

    private String stateJson(Session session) {
        StringBuilder json = new StringBuilder("{\"users\":[");
        boolean first = true;
        for (User user : service.getUsers()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":").append(quote(user.getId()))
                    .append(",\"name\":").append(quote(user.getName()))
                    .append('}');
        }
        json.append("],\"groups\":[");
        first = true;
        for (Group group : service.getGroups()) {
            if (!group.hasMember(session.userId)) continue;
            if (!first) json.append(',');
            first = false;
            json.append(groupJson(group));
        }
        return json.append("]}").toString();
    }

    private String groupJson(Group group) {
        StringBuilder json = new StringBuilder("{\"id\":").append(quote(group.getId()))
                .append(",\"name\":").append(quote(group.getName())).append(",\"members\":[");
        boolean first = true;
        for (String memberId : group.getMemberIds()) {
            if (!first) json.append(',');
            first = false;
            json.append(quote(memberId));
        }
        json.append("],\"balances\":[");
        first = true;
        for (Map.Entry<User, BigDecimal> entry : service.getBalances(group.getId()).entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"userId\":").append(quote(entry.getKey().getId()))
                    .append(",\"amount\":").append(quote(entry.getValue().toPlainString())).append('}');
        }
        json.append("],\"debts\":[");
        first = true;
        for (Debt debt : service.getSuggestedSettlements(group.getId())) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"debtorId\":").append(quote(debt.getDebtor().getId()))
                    .append(",\"creditorId\":").append(quote(debt.getCreditor().getId()))
                    .append(",\"amount\":").append(quote(debt.getAmount().toPlainString())).append('}');
        }
        json.append("],\"expenses\":[");
        first = true;
        for (Expense expense : service.getExpenses(group.getId())) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":").append(quote(expense.getId()))
                    .append(",\"description\":").append(quote(expense.getDescription()))
                    .append(",\"payerId\":").append(quote(expense.getPayerId()))
                    .append(",\"amount\":").append(quote(expense.getAmount().toPlainString()))
                    .append(",\"createdAt\":").append(quote(DATE_FORMAT.format(expense.getCreatedAt())))
                    .append(",\"shares\":{");
            boolean firstShare = true;
            for (Map.Entry<String, BigDecimal> share : expense.getShares().entrySet()) {
                if (!firstShare) json.append(',');
                firstShare = false;
                json.append(quote(share.getKey())).append(':').append(quote(share.getValue().toPlainString()));
            }
            json.append("}}");
        }
        json.append("],\"settlements\":[");
        first = true;
        for (Settlement settlement : service.getSettlements(group.getId())) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":").append(quote(settlement.getId()))
                    .append(",\"payerId\":").append(quote(settlement.getPayerId()))
                    .append(",\"recipientId\":").append(quote(settlement.getRecipientId()))
                    .append(",\"amount\":").append(quote(settlement.getAmount().toPlainString()))
                    .append(",\"createdAt\":").append(quote(DATE_FORMAT.format(settlement.getCreatedAt())))
                    .append('}');
        }
        return json.append("]}").toString();
    }

    private byte[] backupZip() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("equalizer-data.properties"));
            zip.write(service.exportBackup());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("equalizer-accounts.properties"));
            zip.write(accounts.exportAccounts());
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private void restoreZip(byte[] data) throws IOException {
        byte[] dataBackup = null;
        byte[] accountBackup = null;
        int expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!"equalizer-data.properties".equals(name)
                        && !"equalizer-accounts.properties".equals(name)) {
                    throw new IllegalArgumentException("The selected file is not an Equalizer backup.");
                }
                byte[] contents = readLimited(zip, MAX_BACKUP_SIZE - expanded);
                expanded += contents.length;
                if ("equalizer-data.properties".equals(name)) {
                    dataBackup = contents;
                } else {
                    accountBackup = contents;
                }
            }
        }
        if (dataBackup == null || accountBackup == null) {
            throw new IllegalArgumentException("The backup must contain both data and account files.");
        }
        byte[] oldData = service.exportBackup();
        Path temp = Files.createTempFile("equalizer-backup-check-", ".properties");
        try {
            Files.write(temp, dataBackup);
            EqualizerService check = new EqualizerService(temp);
            AccountManager.validateBackupForService(accountBackup, check);
            service.restoreBackup(dataBackup);
            try {
                accounts.restoreAccounts(accountBackup, service);
            } catch (Exception failure) {
                service.restoreBackup(oldData);
                throw failure;
            }
        } catch (IllegalArgumentException | IOException failure) {
            if (!java.util.Arrays.equals(oldData, service.exportBackup())) {
                service.restoreBackup(oldData);
            }
            throw failure;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Session createSession(String userId) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session session = new Session(token, userId, Instant.now().getEpochSecond());
        sessions.put(token, session);
        return session;
    }

    private Session session(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2 || !"equalizer_session".equals(pair[0])) continue;
            Session session = sessions.get(pair[1]);
            if (session != null) {
                if (Instant.now().getEpochSecond() - session.createdAt > SESSION_SECONDS
                        || accounts.getAccount(session.userId) == null) {
                    sessions.remove(session.token);
                    return null;
                }
                return session;
            }
        }
        return null;
    }

    private void requireGroupAccess(Session session, String groupId) {
        if (!service.getGroup(groupId).hasMember(session.userId)) {
            throw new ForbiddenException("You do not have access to that group.");
        }
    }

    private void requireAdmin(Session session) {
        AccountManager.Account account = accounts.getAccount(session.userId);
        if (account == null || !account.admin()) {
            throw new ForbiddenException("Only the Equalizer administrator can do that.");
        }
    }

    private static void requireSameOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (origin != null && (host == null || !origin.equals("https://" + host))) {
            throw new ForbiddenException("Requests must come from this Equalizer page.");
        }
    }

    private static boolean isAuthPath(String path) {
        return path.startsWith("/api/auth/");
    }

    private static void setCookie(HttpExchange exchange, String token) {
        exchange.getResponseHeaders().add("Set-Cookie",
                "equalizer_session=" + token + "; Path=/; Max-Age=" + SESSION_SECONDS
                        + "; HttpOnly; Secure; SameSite=Strict");
    }

    private static void clearCookie(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Set-Cookie",
                "equalizer_session=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict");
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        byte[] body = readLimited(exchange, 65_536);
        Map<String, String> values = new LinkedHashMap<>();
        String form = new String(body, StandardCharsets.UTF_8);
        for (String pair : form.split("&")) {
            if (pair.isEmpty()) continue;
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private static byte[] readLimited(HttpExchange exchange, int limit) throws IOException {
        return readLimited(exchange.getRequestBody(), limit);
    }

    private static byte[] readLimited(java.io.InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IllegalArgumentException("Request is too large.");
        return bytes;
    }

    private static String required(Map<String, String> form, String key) {
        String value = form.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required field: " + key + ".");
        }
        return value.trim();
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
            }
        }
        return escaped.append('"').toString();
    }

    private static String readIndex() throws IOException {
        Path[] candidates = {Path.of("web", "index.html"), Path.of("src", "web", "index.html")};
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return Files.readString(candidate, StandardCharsets.UTF_8);
        }
        throw new IOException("Could not find web/index.html. Start Equalizer from the project directory.");
    }

    private static SSLContext createSslContext(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path keyStoreFile = directory.resolve("equalizer.p12");
        Path passwordFile = directory.resolve("keystore-password.txt");
        Path certificateFile = directory.resolve("equalizer-local-cert.cer");
        if (!Files.exists(keyStoreFile) && !Files.exists(passwordFile)) {
            byte[] generatedPassword = new byte[24];
            new SecureRandom().nextBytes(generatedPassword);
            String password = Base64.getUrlEncoder().withoutPadding().encodeToString(generatedPassword);
            Files.writeString(passwordFile, password, StandardCharsets.US_ASCII,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        if (!Files.exists(passwordFile)) {
            throw new IOException("Missing TLS keystore password file: " + passwordFile);
        }
        String password = Files.readString(passwordFile, StandardCharsets.US_ASCII).trim();
        if (password.isEmpty()) throw new IOException("The TLS keystore password file is empty.");
        boolean generatedKeyStore = false;
        if (!Files.exists(keyStoreFile)) {
            String san = "dns:localhost,ip:127.0.0.1";
            for (String address : lanAddresses()) san += ",ip:" + address;
            runKeytool(List.of("keytool", "-genkeypair", "-alias", "equalizer",
                    "-keyalg", "RSA", "-keysize", "3072", "-validity", "825",
                    "-storetype", "PKCS12", "-keystore", keyStoreFile.toString(),
                    "-storepass:file", passwordFile.toString(), "-keypass:file", passwordFile.toString(),
                    "-dname", "CN=Equalizer local",
                    "-ext", "SAN=" + san, "-noprompt"));
            generatedKeyStore = true;
        }
        if (generatedKeyStore || !Files.exists(certificateFile)) {
            runKeytool(List.of("keytool", "-exportcert", "-rfc", "-alias", "equalizer",
                    "-keystore", keyStoreFile.toString(), "-storepass:file", passwordFile.toString(),
                    "-file", certificateFile.toString()));
        }
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keyStoreFile)) {
                keyStore.load(input, password.toCharArray());
            }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keyStore, password.toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), null, new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IOException("Could not load the local HTTPS certificate.", e);
        }
    }

    private static void runKeytool(List<String> command) throws IOException {
        Process process;
        try {
            List<String> executableCommand = new ArrayList<>(command);
            Path javaTool = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
            if (Files.exists(javaTool)) executableCommand.set(0, javaTool.toString());
            process = new ProcessBuilder(executableCommand).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            int status = process.waitFor();
            if (status != 0) {
                throw new IOException("Could not create the local HTTPS certificate. "
                        + new String(output, StandardCharsets.UTF_8).trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Certificate creation was interrupted.", e);
        }
    }

    private static List<String> lanAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> inetAddresses = network.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress address = inetAddresses.nextElement();
                    if (address.isSiteLocalAddress() && address.getHostAddress().indexOf(':') < 0) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Could not enumerate Wi-Fi addresses: " + e.getMessage());
        }
        return addresses.stream().distinct().toList();
    }

    private static void sendJsonError(HttpExchange exchange, int status, String message)
            throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                "{\"error\":" + quote(message == null ? "Request failed." : message) + "}");
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        sendBytes(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8), null);
    }

    private static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] response,
                                  String disposition) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'");
        if (disposition != null) headers.set("Content-Disposition", disposition);
        exchange.sendResponseHeaders(status, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private record Session(String token, String userId, long createdAt) {}

    private static final class AuthenticationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        AuthenticationException(String message) { super(message); }
    }

    private static final class ForbiddenException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        ForbiddenException(String message) { super(message); }
    }
}
