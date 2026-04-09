# SnipHive for JetBrains

A secure, end-to-end encrypted code snippet manager for JetBrains IDEs.

Access your SnipHive code snippets directly in your JetBrains IDE with full E2EE support. Create, browse, and insert snippets without leaving your development environment.

## Features

- **Tool Window Integration** - Browse and search your snippets in a dedicated tool window
- **Create from Selection** - Instantly create snippets from selected code (`Shift+Alt+S`)
- **Insert Snippets** - Insert snippets via action (`Shift+Alt+I`) or code completion
- **End-to-End Encryption** - Client-side encryption using RSA-4096 OAEP + AES-256-GCM
- **Secure Storage** - Credentials and keys stored in IDE Password Safe
- **Multi-Language Support** - Works with all JetBrains IDEs (IntelliJ IDEA, PhpStorm, PyCharm, WebStorm, etc.)
- **Smart Search** - Filter by language, tags, and search text
- **Code Completion** - Get snippet suggestions as you type

## Security

SnipHive uses industry-standard end-to-end encryption:

- **RSA-4096 OAEP** for key exchange
- **AES-256-GCM** for content encryption
- **PBKDF2** with 600,000 iterations for key derivation
- Your encryption keys never leave your device
- Server never sees your plaintext content

## Installation

### From JetBrains Marketplace

1. Open your JetBrains IDE
2. Go to **Settings/Preferences** → **Plugins**
3. Search for "SnipHive"
4. Click **Install**
5. Restart your IDE

### From Plugin ZIP

1. Download the plugin ZIP from [GitHub Releases](https://github.com/yourorg/sniphive/releases)
2. Go to **Settings/Preferences** → **Plugins**
3. Click the gear icon → **Install Plugin from Disk...**
4. Select the downloaded ZIP file
5. Restart your IDE

## Getting Started

### 1. Create a SnipHive Account

If you don't have an account, visit [https://sniphive.com](https://sniphive.com) to sign up.

### 2. Login to the Plugin

1. Open the SnipHive tool window (View → Tool Windows → SnipHive)
2. Click the **Login** button
3. Enter your SnipHive credentials
4. Your snippets will load automatically

### 3. (Optional) Setup End-to-End Encryption

1. Go to **SnipHive** → **Setup E2EE...**
2. Create a master password (minimum 8 characters)
3. **Important:** Save the recovery code shown - it's the only way to recover your encrypted snippets if you forget your password

## Usage

### Creating Snippets

**From Selected Code:**
1. Select code in your editor
2. Right-click → **Create Snippet** (or press `Shift+Alt+S`)
3. Enter title, select language and tags
4. Click **Create**

**Via Tool Window:**
1. Click the **+** button in the tool window
2. Enter snippet details
3. Click **Create**

### Inserting Snippets

**Via Action:**
1. Position your cursor where you want to insert
2. Right-click → **Insert Snippet...** (or press `Shift+Alt+I`)
3. Search and select a snippet
4. Click **Insert**

**Via Code Completion:**
1. Start typing a snippet title (2+ characters)
2. Select from the completion suggestions
3. The snippet content is inserted

### Searching Snippets

1. Open the SnipHive tool window
2. Use the search field to filter by text
3. Filter by language using the dropdown
4. Select tags for additional filtering

### End-to-End Encryption

**Setup:** Go to SnipHive → Setup E2EE...

**Unlock:** When you open an encrypted snippet, you'll be prompted to enter your master password.

**Recovery:** If you forget your master password, use your recovery code (available during setup or in SnipHive web app).

## Keyboard Shortcuts

| Action | Shortcut |
|--------|----------|
| Create Snippet | `Shift+Alt+S` |
| Insert Snippet | `Shift+Alt+I` |
| Open SnipHive Tool Window | View → Tool Windows → SnipHive |

## Supported IDEs

- IntelliJ IDEA (Ultimate & Community)
- PhpStorm
- PyCharm (Professional & Community)
- WebStorm
- RubyMine
- GoLand
- CLion
- Rider
- DataGrip
- AppCode
- Android Studio

**Minimum Version:** 2023.2

## Configuration

Access plugin settings via **Settings/Preferences** → **Tools** → **SnipHive**:

- **API Configuration** - Custom API URL (for self-hosted deployments)
- **Workspace** - Select your workspace (if you have multiple)
- **Display Options** - Show/hide encrypted content placeholders
- **Auto-Refresh** - Configure automatic refresh interval
- **Code Completion** - Configure completion behavior

## Troubleshooting

### Login Issues

- Verify your SnipHive account credentials
- Check your internet connection
- Ensure the API URL is correct (default: `https://api.sniphive.com`)

### Encrypted Snippets Not Decrypting

- Make sure E2EE is set up in the plugin
- Verify you're using the correct master password
- Use your recovery code if you've forgotten your password
- Check that the workspace has E2EE enabled

### Snippets Not Loading

- Verify you're logged in
- Check your internet connection
- Try refreshing the snippet list
- Check the IDE event log for error messages

### Code Completion Not Working

- Ensure code completion is enabled in plugin settings
- Verify you're typing at least 2 characters for prefix matching
- Check that you're authenticated

## Development

### Building from Source

```bash
# Clone repository
git clone https://github.com/yourorg/sniphive.git
cd sniphive/extensions/jetbrains-sniphive

# Build plugin
./gradlew buildPlugin

# Run in test IDE
./gradlew runIde

# Run tests
./gradlew test
```

### Project Structure

```
extensions/jetbrains-sniphive/
├── src/
│   ├── main/
│   │   ├── kotlin/com/sniphive/idea/
│   │   │   ├── actions/          # IDE actions
│   │   │   ├── completion/       # Code completion provider
│   │   │   ├── config/           # Settings and configuration
│   │   │   ├── crypto/           # E2EE cryptography
│   │   │   ├── models/           # API data models
│   │   │   ├── services/         # Business logic services
│   │   │   ├── toolwindow/       # Tool window factory
│   │   │   └── ui/               # UI components
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   └── plugin.xml    # Plugin descriptor
│   │       └── icons/            # Plugin icons
│   └── test/
│       └── kotlin/com/sniphive/idea/
│           └── crypto/           # Cryptography tests
├── build.gradle.kts              # Gradle build configuration
└── gradlew                      # Gradle wrapper
```

## Anonymous Sharing Security

When copying public URLs for encrypted snippets:
- The URL contains an encryption key (DEK) in the hash fragment
- Anyone with the URL can decrypt and view the content
- Share via secure channels only (avoid email, chat)
- URL will be visible in recipient's browser history

## Privacy & Data

- **End-to-End Encryption** - Your encrypted snippets are stored on SnipHive servers, but the server cannot decrypt them
- **Local Storage** - Credentials and keys are stored in your IDE's Password Safe (encrypted local storage)
- **No Tracking** - No usage analytics or telemetry
- **Open Source** - The plugin code is open source for security audits

## Support

- **Website:** https://sniphive.com
- **Documentation:** https://docs.sniphive.com
- **Issues:** https://github.com/yourorg/sniphive/issues
- **Email:** support@sniphive.com

## License

Copyright © 2024 SnipHive. All rights reserved.

## Credits

Built with:
- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [Kotlin](https://kotlinlang.org/)
- [OkHttp](https://square.github.io/okhttp/)
- [Bouncy Castle](https://www.bouncycastle.org/)
