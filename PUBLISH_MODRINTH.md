# Publishing Vanish++ to Modrinth

Automated script to publish new versions to Modrinth with zero manual configuration.

## What It Does Automatically

✅ **Extracts version** from `pom.xml`  
✅ **Builds JAR** from `target/vanishpp-*.jar`  
✅ **Extracts changelog** for that specific version from `CHANGELOG.md`  
✅ **Reads description** from `DESCRIPTION.md`  
✅ **Sets game versions** to 1.20.6 and 1.21.x automatically  
✅ **Configures platforms** (Paper, Folia, Purpur, Spigot, Bukkit)  
✅ **Configures loaders** (Bukkit, Folia, Paper, Purpur, Spigot)  
✅ **Sets release type** (--release by default, or --beta)  
✅ **Updates project description** on Modrinth  
✅ **Uploads JAR file**  

## Prerequisites

### 1. Install curl (usually pre-installed)
```bash
# macOS
brew install curl

# Linux
sudo apt-get install curl

# Windows (via Git Bash)
# curl is included
```

### 2. Get Modrinth API Token

1. Go to https://modrinth.com/user/settings/profile
2. Scroll to "API Tokens"
3. Click "Create" to generate a new token
4. Copy the token (you'll use it in the next step)

### 3. Set Environment Variable

```bash
# On macOS/Linux (bash)
export MODRINTH_TOKEN="your-token-here"

# On macOS/Linux (zsh)
export MODRINTH_TOKEN="your-token-here"

# On Windows (PowerShell)
$env:MODRINTH_TOKEN="your-token-here"

# Make it persistent (Linux/macOS):
echo 'export MODRINTH_TOKEN="your-token-here"' >> ~/.bashrc
source ~/.bashrc
```

## Usage

### 1. Build the plugin

```bash
mvn clean package -DskipTests -q
```

This creates `target/vanishpp-X.X.X.jar`

### 2. Publish as Release (Default)

```bash
MODRINTH_TOKEN=your-token bash publish-modrinth.sh
```

Or explicitly:
```bash
bash publish-modrinth.sh --release
```

### 3. Publish as Beta

```bash
bash publish-modrinth.sh --beta
```

### 4. Review and Confirm

The script will:
- Display all extracted metadata
- Show you what will be published
- Ask for confirmation (y/N)
- Upload to Modrinth

Example output:
```
ℹ Reading version from pom.xml...
✓ Version: 1.1.6
ℹ Checking JAR file...
✓ JAR found: /path/to/target/vanishpp-1.1.6.jar (3071234 bytes)
ℹ Extracting changelog for v1.1.6...
✓ Changelog extracted (1234 chars)
ℹ Reading description from DESCRIPTION.md...
✓ Description loaded

Configuration:
  Project ID:      vanishpp
  Version:         1.1.6
  Release Type:    release
  JAR File:        vanishpp-1.1.6.jar
  File Size:       3071234 bytes
  Game Versions:   1.20.6, 1.21.x
  Platforms:       Paper, Folia, Purpur, Spigot, Bukkit
  Loaders:         Bukkit, Folia, Paper, Purpur, Spigot

⚠ Ready to publish to Modrinth
Continue? (y/N) y

ℹ Uploading to Modrinth...
✓ Description updated
✓ Version uploaded successfully!

✓ Successfully published to Modrinth!

  Project:  https://modrinth.com/plugin/vanish++/versions
  Version:  1.1.6
  Release:  release
```

## How It Extracts Data

### Version
Reads from `pom.xml`:
```xml
<version>1.1.6</version>
```

### Changelog
Extracts from `CHANGELOG.md` the section for that version:
```markdown
## [1.1.6] - 2026-04-04

### Added
- Feature 1
- Feature 2

### Fixed
- Fix 1
```
Everything between the version header and the next version header is included.

### Description
Reads entire contents of `DESCRIPTION.md` and uses as project description on Modrinth.

### Game Versions
Automatically set to: `1.20.6, 1.21, 1.21.1, 1.21.2, ..., 1.21.11`

### Platforms
Automatically set to: `Paper, Folia, Purpur, Spigot, Bukkit`

### Loaders
Automatically set to: `Bukkit, Folia, Paper, Purpur, Spigot`

## Troubleshooting

### "MODRINTH_TOKEN environment variable not set"
```bash
export MODRINTH_TOKEN="your-token-here"
```

### "JAR file not found"
```bash
# Make sure you built the plugin first
mvn clean package -DskipTests -q
```

### "CHANGELOG.md not found for version X.X.X"
Make sure the version in `pom.xml` matches a section in `CHANGELOG.md`:
```markdown
## [1.1.6] - 2026-04-04
  ↑ This must match the pom.xml version exactly
```

### "Upload failed with HTTP 401"
Your MODRINTH_TOKEN is invalid or expired. Generate a new one:
1. Go to https://modrinth.com/user/settings/profile
2. Delete the old token
3. Create a new one
4. Update your environment variable

### "Upload failed with HTTP 403"
You don't have permission to upload to this project. Make sure:
- You're logged in as the project owner
- The token is for your account
- The project ID is correct (default: vanishpp)

## Customization

### Change Project ID
```bash
MODRINTH_PROJECT_ID=my-plugin bash publish-modrinth.sh --release
```

### Change Minecraft Versions
Edit the `build_game_versions()` function in `publish-modrinth.sh`:
```bash
build_game_versions() {
    cat <<'EOF'
["1.20", "1.20.1", "1.21", "1.21.1"]
EOF
}
```

### Change Supported Platforms
Edit the `build_loaders()` function:
```bash
build_loaders() {
    cat <<'EOF'
["fabric", "forge"]
EOF
}
```

## Security Notes

⚠️ **Never commit your MODRINTH_TOKEN to Git**

Good practices:
- Store token in environment variable only (not in files)
- Use `.bashrc`, `.zshrc`, or your shell's profile
- Don't share the token with others
- Rotate tokens periodically
- Use different tokens for different projects if possible

## Automation Examples

### GitHub Actions (Optional Future Setup)

If you want to automate this in CI/CD:

```yaml
name: Publish to Modrinth
on:
  push:
    tags:
      - 'v*'

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK
        uses: actions/setup-java@v2
        with:
          java-version: '21'
      - name: Build
        run: mvn clean package -DskipTests -q
      - name: Publish to Modrinth
        env:
          MODRINTH_TOKEN: ${{ secrets.MODRINTH_TOKEN }}
        run: bash publish-modrinth.sh --release
```

## Support

If something goes wrong:

1. Check the error message (usually very descriptive)
2. Verify environment variables are set: `echo $MODRINTH_TOKEN`
3. Test JAR exists: `ls -lh target/vanishpp-*.jar`
4. Check version matches: `grep <version> pom.xml` vs `CHANGELOG.md`
5. Verify changelog format in `CHANGELOG.md`

---

**Last Updated**: 2026-04-04  
**Script**: `publish-modrinth.sh`  
**Status**: Ready to use
