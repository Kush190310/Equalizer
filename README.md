# Equalizer

Equalizer is a dependency-free Java expense-sharing application with a responsive browser UI. It supports accounts, groups, shared expenses, balances, settlements, edit/delete, local backups, and HTTPS access on a trusted local network.

## Run on Windows

From the project directory:

```powershell
javac -Xlint:all -d out src\*.java
java -cp out Main
```

Equalizer creates a local HTTPS certificate on first launch and opens [https://localhost:8443](https://localhost:8443). Keep the terminal open while using the app and press `Ctrl+C` to stop it. Java's `keytool` must be available in the active JDK.

The certificate is self-signed. Browsers will warn until you explicitly trust `data\tls\equalizer-local-cert.cer` on the device. Only install/trust this certificate for your own Equalizer server; do not share the private keystore or its password. The certificate and private key stay in `data\tls`, which is excluded from Git.

### Accounts and existing data

- The first account created becomes the administrator. Later accounts can access only groups in which their account is a member.
- Sign-up is available to people who can reach the server, so share the Wi-Fi URL only with people you trust.
- If an older Equalizer data file contains people but no accounts, startup preserves it as `data\legacy-before-accounts.properties` and starts the account-protected workspace empty. The old file is not deleted.
- App records are stored in `data\equalizer-data.properties`; account password hashes are stored separately in `data\accounts.properties`. Passwords are hashed with PBKDF2-HMAC-SHA-256 and are not stored in plaintext.
- Admins can download or restore a ZIP backup. It contains the app data and password hashes, so keep it private. A restore replaces all current accounts and records; after restoring, sign in with an account from that backup.
- **Clear all data** removes accounts as well as records. The next account created becomes the new administrator.

### Use from another device on the same Wi-Fi

1. Start Equalizer on the computer that hosts it. The terminal prints the available `https://<LAN-IP>:8443` address.
2. On each device, install/trust `data\tls\equalizer-local-cert.cer` as a trusted certificate so the browser can authenticate the local HTTPS connection.
3. If Windows Firewall blocks access, allow inbound TCP port `8443` on the **Private** network profile only. Do not expose this port to the public internet.
4. Open the printed HTTPS address and sign in or create an account.

The server listens on all network interfaces for Wi-Fi access. Use it only on a trusted private network; it is not intended for public hosting.

## Features

- Create an account and sign in with a secure, HTTP-only HTTPS session.
- Create groups, then add a signed-up account from the account directory to a group.
- Record expenses with equal or exact-cent splits.
- View group balances and suggested debt-settlement transfers.
- Record, edit, and delete expenses; delete settlements and recalculate balances.
- Download and restore a validated backup; destructive actions require administrator access.
- Switch between the multi-color light and dark themes; the selection is saved in the browser.

Money amounts must be positive, with no more than two decimal places. Exact shares must be non-negative and add up to the expense total.

## Console mode

The legacy console interface runs with in-memory data for that process:

```powershell
java -cp out Main --console
```

## Tests

```powershell
javac -Xlint:all -d out src\*.java
javac -Xlint:all -cp out -d out test\EqualizerPersistenceTest.java
java -cp out EqualizerPersistenceTest
```
