# Vanish++ Changelog

All notable changes to this project will be documented in this file.

## [1.0.1] - 2025-06-09

### Added
- **Simple Permissions System:** A `permissions.yml` file is now generated, allowing server owners to grant specific Vanish++ permissions to non-OP players without needing a dedicated permissions plugin like LuckPerms.
- **Permissions Management Command:** Added the `/vperms` command to manage the simple permissions system in-game. It is protected by `vanishpp.manageperms` and features full tab-completion.
- **Advanced Arrow Stealth:** Vanished players no longer block or stutter arrows. Projectiles now pass seamlessly through them as if they weren't there, leaving no trace of the vanished player's presence.

### Fixed
- **Data Persistence:** Vanish state is now saved instantly and asynchronously the moment a player's state changes, ensuring no data is lost on server crash or unexpected shutdown.
- **Join Message Bug:** Corrected a timing issue where players who were vanished during a server restart would sometimes trigger a "Player joined the game" message with a vanish prefix. This is now handled silently and correctly.