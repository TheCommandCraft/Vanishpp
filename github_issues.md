# Vanish++ GitHub Issues Evaluation

## 🟢 Already Implemented (Available in v1.2.0)

### 1. Allow editing of words used in the plugin #8
*   **Status:** Implemented.
*   **Response:** We have introduced a full localization system. All plugin messages, warnings, and interactive buttons (e.g., `[ENABLE]`) are now fully editable in the `lang/en.yml` file.

### 2. Add command /vconfig reload #7
*   **Status:** Implemented.
*   **Response:** We added the `/vanishreload` command (alias `/vreload`), which instantly reloads the configuration and language files without requiring a server reboot or external plugin managers.

### 3. Add support for Minimessage and PlaceholderAPI #6
*   **Status:** Implemented.
*   **Response:** The plugin now natively utilizes the MiniMessage format (e.g., `<red>`, `<bold>`) for rich text rendering and fully integrates with PlaceholderAPI for dynamic text replacements across all messages.

### 4. Add Folia support #5
*   **Status:** Implemented.
*   **Response:** A custom regional scheduler bridge has been implemented, providing out-of-the-box compatibility with Folia's multi-threaded server architecture.

---

## 🟡 Planned (Prioritized Bug Fixes / Minor Changes)

### 1. Fly during vanish #3
*   **Status:** Planned (Prioritized).
*   **Response:** Currently, the plugin utilizes the `flight-control.unvanish-disable-fly` setting. However, we understand this interferes with existing rank permissions. We will immediately update this logic to check if a player inherently has fly permission (e.g., via Essentials or Bukkit base perms); if so, we will safely preserve their flight state upon unvanishing.

### 2. Add DiscordSRV hook please #2
*   **Status:** Planned (Prioritized).
*   **Response:** We have introduced a foundational DiscordSRV hook to prevent chat duplication. We will now prioritize expanding this hook to intercept and suppress DiscordSRV join and quit messages when a player vanishes or unvanishes, ensuring absolute stealth on Discord as well.

---

## 🔴 Planned for Later (Large Architectural Changes)

### 1. Add Velocity support #4
*   **Status:** Planned Later.
*   **Response:** Creating seamless network-wide persistence for vanish states across proxy boundaries (Velocity/Redis) requires a substantial architectural expansion. While highly requested and definitely on our roadmap for a future major release, we are currently prioritizing bug fixes and core backend stability.
