#!/bin/bash

################################################################################
#                                                                              #
#   Vanish++ — Modrinth Auto-Publisher                                        #
#                                                                              #
#   Automatically publishes to Modrinth with:                                 #
#   - Version from pom.xml                                                    #
#   - Changelog extracted from CHANGELOG.md                                    #
#   - Description from DESCRIPTION.md                                         #
#   - Game versions, platforms, loader auto-detected                          #
#                                                                              #
#   Usage:                                                                    #
#     MODRINTH_TOKEN=<your-token> bash publish-modrinth.sh [--release]       #
#                                                                              #
#   Environment Variables:                                                    #
#     MODRINTH_TOKEN      - Your Modrinth API token (required)               #
#     MODRINTH_PROJECT_ID - Project ID (default: vanishpp)                  #
#                                                                              #
#   Examples:                                                                 #
#     # Build and publish as beta                                            #
#     mvn clean package -DskipTests -q                                       #
#     MODRINTH_TOKEN=abc123 bash publish-modrinth.sh --beta                 #
#                                                                              #
#     # Publish as release                                                    #
#     MODRINTH_TOKEN=abc123 bash publish-modrinth.sh --release              #
#                                                                              #
################################################################################

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POM_FILE="$SCRIPT_DIR/pom.xml"
CHANGELOG_FILE="$SCRIPT_DIR/CHANGELOG.md"
DESCRIPTION_FILE="$SCRIPT_DIR/DESCRIPTION.md"
TARGET_DIR="$SCRIPT_DIR/target"

MODRINTH_PROJECT_ID="${MODRINTH_PROJECT_ID:-vanishpp}"
MODRINTH_API_URL="https://api.modrinth.com/v2"
MODRINTH_TOKEN="mrp_tqXxk8M7AvhGtYXrIWXRIvXmEbOCUXYhYpGQchJRkfW5iigkIHqQeSmbbKnu"
RELEASE_TYPE="${1:---release}"  # --release (default) or --beta

# Validate release type
if [[ "$RELEASE_TYPE" != "--release" && "$RELEASE_TYPE" != "--beta" ]]; then
    echo -e "${RED}✗ Invalid release type: $RELEASE_TYPE${NC}"
    echo "  Use: --release or --beta"
    exit 1
fi

# Convert to modrinth format
if [[ "$RELEASE_TYPE" == "--release" ]]; then
    MODRINTH_RELEASE_TYPE="release"
else
    MODRINTH_RELEASE_TYPE="beta"
fi

# Check for required environment variable
if [[ -z "${MODRINTH_TOKEN:-}" ]]; then
    echo -e "${RED}✗ MODRINTH_TOKEN environment variable not set${NC}"
    echo "  Set it with: export MODRINTH_TOKEN=<your-token>"
    exit 1
fi

# Check for required files
if [[ ! -f "$POM_FILE" ]]; then
    echo -e "${RED}✗ pom.xml not found at $POM_FILE${NC}"
    exit 1
fi

if [[ ! -f "$CHANGELOG_FILE" ]]; then
    echo -e "${RED}✗ CHANGELOG.md not found at $CHANGELOG_FILE${NC}"
    exit 1
fi

if [[ ! -f "$DESCRIPTION_FILE" ]]; then
    echo -e "${RED}✗ DESCRIPTION.md not found at $DESCRIPTION_FILE${NC}"
    exit 1
fi

################################################################################
# Helper Functions
################################################################################

log_info() {
    echo -e "${BLUE}ℹ${NC} $1"
}

log_success() {
    echo -e "${GREEN}✓${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

log_error() {
    echo -e "${RED}✗${NC} $1"
}

# Escape a string for use in JSON (no trailing space)
escape_json() {
    local s="$1"
    s="${s//\\/\\\\}"
    s="${s//\"/\\\"}"
    s="${s//$'\t'/\\t}"
    s="${s//$'\r'/\\r}"
    s="${s//$'\n'/\\n}"
    printf '%s' "$s"
}

# Extract version from pom.xml
extract_version() {
    grep -oPm1 '(?<=<version>)[^<]+' "$POM_FILE" | head -1
}

# Extract changelog section for specific version
extract_changelog() {
    local version="$1"
    local in_section=0
    local changelog=""
    local line_num=0

    while IFS= read -r line; do
        line_num=$((line_num + 1))

        # Check if this is a version header line
        if [[ "$line" =~ ^##\ \[.*\] ]]; then
            if [[ $in_section -eq 1 ]]; then
                # We've hit the next version, stop
                break
            fi
            if [[ "$line" == "## [$version]"* ]]; then
                in_section=1
                # Skip the header line itself and any date info
                continue
            fi
        fi

        if [[ $in_section -eq 1 ]]; then
            changelog+="$line"$'\n'
        fi
    done < "$CHANGELOG_FILE"

    # Clean up: remove leading/trailing empty lines
    echo "$changelog" | sed '1d' | sed -e :a -e '/^\s*$/d;N;ba' | head -c -1
}

# Get JAR file path
get_jar_path() {
    local version="$1"
    local jar_file="$TARGET_DIR/vanishpp-$version.jar"

    if [[ ! -f "$jar_file" ]]; then
        log_error "JAR file not found: $jar_file"
        log_info "Please run: mvn clean package -DskipTests -q"
        exit 1
    fi

    echo "$jar_file"
}

# Get file size in bytes
get_file_size() {
    stat -f%z "$1" 2>/dev/null || stat -c%s "$1" 2>/dev/null || echo "0"
}

# Convert markdown to plain text for changelog (basic)
markdown_to_plain() {
    sed 's/^### //' | sed 's/^## //' | sed 's/\*\*//g' | sed 's/^\- /• /'
}

# Upload version to Modrinth via API
upload_to_modrinth() {
    local jar_path="$1"
    local version="$2"
    local changelog_body="$3"
    local file_size="$4"

    log_info "Preparing upload..."

    # Build JSON payload with proper escaping
    local payload
    local name_escaped
    local changelog_escaped
    local version_escaped

    name_escaped=$(escape_json "Vanish++ v$version")
    changelog_escaped=$(escape_json "$changelog_body")
    version_escaped=$(escape_json "$version")

    payload="{
  \"name\": \"$name_escaped\",
  \"version_number\": \"$version_escaped\",
  \"changelog\": \"$changelog_escaped\",
  \"dependencies\": [],
  \"game_versions\": [\"1.20.6\", \"1.21\", \"1.21.1\", \"1.21.2\", \"1.21.3\", \"1.21.4\", \"1.21.5\", \"1.21.6\", \"1.21.7\", \"1.21.8\", \"1.21.9\", \"1.21.10\", \"1.21.11\"],
  \"release_channel\": \"$MODRINTH_RELEASE_TYPE\",
  \"loaders\": [\"bukkit\", \"folia\", \"paper\", \"purpur\", \"spigot\"],
  \"featured\": true,
  \"project_id\": \"$MODRINTH_PROJECT_ID\",
  \"primary_file\": \"0\",
  \"file_parts\": [\"0\"]
}"

    log_info "Uploading JAR ($file_size bytes)..."

    # Upload file to Modrinth using multipart form
    # The API requires the JSON data and the file to be uploaded together
    local temp_json
    temp_json=$(mktemp)
    echo "$payload" > "$temp_json"

    local curl_response
    curl_response=$(curl -s -w "\n%{http_code}" \
        -H "Authorization: $MODRINTH_TOKEN" \
        -F "data=@$temp_json;type=application/json" \
        -F "file=@$jar_path" \
        "$MODRINTH_API_URL/version")

    rm -f "$temp_json"

    # Extract HTTP status code from last line
    local http_code
    http_code=$(echo "$curl_response" | tail -n 1)
    local response_body
    response_body=$(echo "$curl_response" | head -n -1)

    if [[ "$http_code" == "200" ]]; then
        log_success "Version uploaded successfully!"
        return 0
    else
        log_error "Upload failed with HTTP $http_code"
        if command -v jq &> /dev/null; then
            echo "$response_body" | jq . 2>/dev/null || echo "$response_body"
        else
            echo "$response_body"
        fi
        return 1
    fi
}

# Update project description on Modrinth
update_project_description() {
    local description="$1"

    log_info "Updating project description..."

    local desc_escaped
    desc_escaped=$(escape_json "$description")
    local payload="{\"description\": \"$desc_escaped\"}"

    local curl_response
    curl_response=$(curl -s -w "\n%{http_code}" \
        -X PATCH \
        -H "Authorization: $MODRINTH_TOKEN" \
        -H "Content-Type: application/json" \
        -d "$payload" \
        "$MODRINTH_API_URL/project/$MODRINTH_PROJECT_ID")

    local http_code
    http_code=$(echo "$curl_response" | tail -n 1)

    if [[ "$http_code" == "204" || "$http_code" == "200" ]]; then
        log_success "Description updated"
        return 0
    else
        log_warning "Failed to update description (HTTP $http_code)"
        return 1
    fi
}

################################################################################
# Main Execution
################################################################################

echo ""
echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║          Vanish++ — Modrinth Auto-Publisher                   ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Step 0: Extract version first to check if build is needed
log_info "Reading version from pom.xml..."
VERSION=$(extract_version)
if [[ -z "$VERSION" ]]; then
    log_error "Failed to extract version from pom.xml"
    exit 1
fi
log_success "Version: $VERSION"

# Step 1: Build if JAR doesn't exist
if [[ ! -f "$TARGET_DIR/vanishpp-$VERSION.jar" ]]; then
    log_info "JAR not found. Building plugin..."
    if ! mvn clean package -DskipTests -q; then
        log_error "Maven build failed!"
        exit 1
    fi
    log_success "Build completed successfully"
fi

# Step 2: Verify JAR exists
log_info "Checking JAR file..."
JAR_PATH=$(get_jar_path "$VERSION")
JAR_SIZE=$(get_file_size "$JAR_PATH")
log_success "JAR found: $JAR_PATH ($JAR_SIZE bytes)"

# Step 3: Extract changelog
log_info "Extracting changelog for v$VERSION..."
CHANGELOG=$(extract_changelog "$VERSION")
if [[ -z "$CHANGELOG" ]]; then
    log_warning "No changelog found for version $VERSION in CHANGELOG.md"
    CHANGELOG="See release notes on GitHub: https://github.com/TheCommandCraft/Vanishpp/releases/tag/$VERSION"
fi
log_success "Changelog extracted (${#CHANGELOG} chars)"

# Step 4: Read description
log_info "Reading description from DESCRIPTION.md..."
DESCRIPTION=$(cat "$DESCRIPTION_FILE")
log_success "Description loaded"

# Step 5: Display configuration
echo ""
echo -e "${BLUE}Configuration:${NC}"
echo -e "  Project ID:      $MODRINTH_PROJECT_ID"
echo -e "  Version:         $VERSION"
echo -e "  Release Type:    $MODRINTH_RELEASE_TYPE"
echo -e "  JAR File:        $(basename "$JAR_PATH")"
echo -e "  File Size:       $JAR_SIZE bytes"
echo -e "  Game Versions:   1.20.6, 1.21.x"
echo -e "  Platforms:       Paper, Folia, Purpur, Spigot, Bukkit"
echo -e "  Loaders:         Bukkit, Folia, Paper, Purpur, Spigot"

# Step 6: Confirm before upload
echo ""
echo -e "${YELLOW}⚠ Ready to publish to Modrinth${NC}"
read -p "Continue? (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    log_info "Cancelled"
    exit 0
fi

# Step 7: Update description (non-fatal — requires WRITE_PROJECTS token scope)
update_project_description "$DESCRIPTION" || true

# Step 8: Upload version
echo ""
log_info "Uploading to Modrinth..."
if upload_to_modrinth "$JAR_PATH" "$VERSION" "$CHANGELOG" "$JAR_SIZE"; then
    echo ""
    echo -e "${GREEN}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║              ✓ Successfully published to Modrinth!             ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "  Project:  ${BLUE}https://modrinth.com/plugin/vanish++/versions${NC}"
    echo -e "  Version:  ${BLUE}$VERSION${NC}"
    echo -e "  Release:  ${BLUE}$MODRINTH_RELEASE_TYPE${NC}"
    echo ""
    exit 0
fi

# If we get here, something failed
echo ""
echo -e "${RED}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${RED}║                   ✗ Publication failed                         ║${NC}"
echo -e "${RED}╚════════════════════════════════════════════════════════════════╝${NC}"
exit 1
