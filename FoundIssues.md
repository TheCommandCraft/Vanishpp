# Vanish++ 1.1.4 — Found Issues (26)

> Discovered during in-game testing on `testserver_clean` with full plugin stack.
> Sorted by category and impact. Fix in order for best results.

---

## 🔴 Critical Bugs — Core Behaviour Broken

| # | Issue | Detail |
|---|-------|--------|
| **3** | **Flight not revoked on unvanish** | `flight-control.unvanish-disable-fly` config has no effect — flight stays after unvanishing regardless of setting. |
| **11** | **`mob_targeting` rule doesn't work** | `/vrules mob_targeting true` does nothing even after `/vreload`. Mobs still ignore vanished players. |
| **12** | **Vanished players can be pushed** | Non-vanished players can push vanished ones. Collision prevention is one-directional only. |
| **16** | **Advancements not suppressed** | Advancement announcements broadcast to all players even when the earner is vanished. |
| **22** | **Interaction rules not enforced** | Beds can be slept in, horses can be mounted, entity interactions are not blocked despite rules being `false`. |

---

## 🟠 Important Bugs — Visible But Not Breaking

| # | Issue | Detail |
|---|-------|--------|
| **2** | **Color codes visible in chat** | All plugin messages display raw `&6`, `§e` codes instead of rendered colors. MiniMessage or legacy parsing not applied. |
| **6** | **Staff notifications missing from console** | Vanish/unvanish staff messages are not printed to console or server logs. |
| **7** | **Silent join/quit notifications not shown** | Staff with `vanishpp.see` do not receive `[+] player silently joined` / `[-] player silently left` messages when a vanished player joins or quits the server. |
| **13** | **Rule block notifications spam** | When a rule prevents an action, the chat message fires every tick with zero cooldown. |
| **14** | **Cannot move items in silently-opened chests** | Spectator mode switch prevents item interaction. Preferred fix: suppress the chest-open packet to other players so spectator mode isn't needed. If ProtocolLib is absent and this isn't possible, show a warning on chest open with a clickable `[Install ProtocolLib]` button linking to `https://www.spigotmc.org/resources/protocollib.1997/` and fall back to the spectator method. |
| **15** | **Ender chest visible / Shulker plays sound** | Opening an ender chest while vanished plays the animation for other players. Shulker box suppresses animation but still emits sound. |
| **17** | **Invalid rule name shows raw lang key** | `/vrules a true` prints the literal string `rules.not-found` instead of the resolved message. |
| **19** | **`/vrules all true <player>` targets executor** | When a player name is appended after `all true`, rules are applied to the executor instead of the specified target. |
| **20** | **Tab prefix shows placeholder ID** | The `[VANISHED]` prefix in the Tab list displays the raw placeholder key string instead of the configured prefix text. |
| **24** | **Nametag prefix missing above player head** | The configured nametag prefix is not shown above the vanished player's head for those with `vanishpp.see`. |
| **10** | **`/vconfig` changes don't apply immediately** | Properties changed via `/vconfig` require a manual `/vreload` to take effect. The command is advertised as a live editor — changes must apply to memory instantly without any reload. |
| **26** | **`/vperms` cannot modify `vlist` permission** | Attempting to grant/revoke `vanishpp.list` (and possibly others) via `/vperms` has no effect. |

---

## 🟡 UX / Interactivity — Hard to Use

| # | Issue | Detail |
|---|-------|--------|
| **1** | **All interactions need to be as easy as possible** | This is a global UX principle, not just setup warnings. Every message that asks the user to do something must make that thing a single click. For the setup warning specifically: highlight config key names and values in color, show a `[Set to false]` button that applies the change immediately, then in the same message show a `[Revert]` button and a reload reminder with a `[Reload]` button. Also show an `[Install Plugin]` button that opens `https://modrinth.com/plugin/simple-voice-chat#download` in the browser. Fresh users must never have to leave the game to figure out what to do. |
| **4** | **Vanish permission denial is visible** | Players without `vanishpp.vanish` get an error message, revealing the command exists. Should silently return "unknown command" to stay stealthy. |
| **5** | **`[Vanished]` prefix not shown anywhere for staff** | Players with `vanishpp.see` should see a `[Vanished]` prefix on the vanished player in **all visible areas** — tab list, chat, and above the head. The prefix must NOT leak into scoreboard or any packets sent to players without the permission. (Nametag specifically tracked in #24.) |
| **8** | **Rule block notifications need action buttons** | When an action is blocked by a rule, the notification should include a `[Allow now]` button (enables for 1 minute) and a `[Allow permanently]` button with a single click. |
| **9** | **No tab-autocomplete on any command** | `/vanish`, `/vrules`, `/vpickup`, `/vperms`, `/vconfig`, `/vack`, `/vhelp`, etc. have zero tab-completion for arguments, subcommands, or player names. |
| **23** | **`/plugins` fake list is unconvincing** | The spoofed list looks obviously fake. Should mirror the real vanilla `/plugins` format as closely as possible. OPs get the **same filtered list** as normal players (Vanishpp hidden), but with an additional note that the list is being filtered and a clickable `[Disable hiding]` button to toggle it off in config. |
| **25** | **`/vhelp <invalid>` silently falls back** | Entering an unknown command name in `/vhelp blargh` just shows the full list with no acknowledgement. Should say "Unknown command 'blargh'" then show the list. |

---

## 🔵 Config / Command Cleanup

| # | Issue | Detail |
|---|-------|--------|
| **18** | **`/vpickup` is redundant** | `/vpickup` does nothing that `/vrules can_pickup_items` doesn't already do. Remove the command unless there is a distinct use case. |
| **21** | **`adjust-server-list-count` key name is verbose** | The `/vconfig` path `vanish-appearance.adjust-server-list-count` is confusing. Rename to `vanish-appearance.adjust-server-list` for clarity. |

---

## 📊 Issue Count by Category

| Category | Count |
|----------|-------|
| 🔴 Critical bugs | 5 |
| 🟠 Important bugs | 12 |
| 🟡 UX / Interactivity | 7 |
| 🔵 Config / Cleanup | 2 |
| **Total** | **26** |
