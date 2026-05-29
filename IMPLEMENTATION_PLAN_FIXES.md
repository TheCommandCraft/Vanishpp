# Implementation Plan — Bug Fixes v1.1.9

**Scope:** 8 critical/blocking issues  
**Estimated Effort:** 6-8 hours total  
**Priority Order:** Blockers first, then UI  
**Target:** Professional client-ready output

---

## Issue 1: Mob Targeting — Prevent Head Rotation

### Problem
Mobs (baby zombies, all mobs) look at/track vanished players. They don't attack but still visually acknowledge presence.

### Root Cause
Missing or incomplete mob target/look-at prevention. User mentions this was working in a prior implementation copied from another vanish plugin.

### Solution
**Search Strategy:**
- Look for `EntityTargetEvent` handler in codebase
- Check for `ZombieAttackGoal`, `LookControl`, `Pathfinder` removal
- Reference: User mentioned copying logic from another plugin before

**Implementation Approach:**
1. Find existing mob-related code (likely in `EventListenerTest` or similar)
2. Enhance `EntityTargetEvent` listener to:
   - Cancel event if target is vanished player
   - Verify no residual look/head-tracking
3. If EntityTargetEvent isn't enough, also handle:
   - `WatcherValueChanged` for head rotation data
   - Remove custom goals that track vanished players
4. Test with: zombies (adult + baby), creepers, skeletons, spiders, endermen
5. Ensure: ZERO visual indication that mob sees vanished player

**Files to Modify:**
- `vanishpp-paper/src/main/java/net/thecommandcraft/vanishpp/listeners/EventListener.java` — Mob target event handler
- May need to review `MobAIManager` or similar if it exists

**Key Code Patterns to Check:**
```java
// Should cancel ALL targeting of vanished players
@EventHandler(priority = EventPriority.HIGHEST)
public void onEntityTarget(EntityTargetEvent event) {
    if (event.getTarget() instanceof Player) {
        Player target = (Player) event.getTarget();
        if (plugin.isVanished(target)) {
            event.setCancelled(true);  // Cancel targeting
            // Also ensure no other tracking mechanisms fire
        }
    }
}
```

**Success Criteria:**
- No mob head rotation toward vanished player
- No mob pathfinding toward vanished player
- All mob types tested passing

---

## Issue 2: Spectator Mode — Gamemode Preservation

### Problem
Running `/v` puts player into Spectator mode instead of keeping their current gamemode.

### Root Cause
Likely in `applyVanishEffects()` — probably explicitly sets Spectator mode.

### Solution
**Find and Fix:**
1. Locate `applyVanishEffects()` in `Vanishpp.java`
2. Find line that does: `player.setGameMode(GameMode.SPECTATOR)` or similar
3. Change logic to:
   - Store current gamemode in a map BEFORE changing anything
   - Do NOT change gamemode when vanishing
   - When unvanishing, restore from map
4. Verify gamemode is preserved across:
   - `/v` (vanish)
   - `/v` (unvanish)
   - World changes
   - Server resets

**Files to Modify:**
- `Vanishpp.java` — `applyVanishEffects()`, `removeVanishEffects()`, cleanup

**Code Pattern:**
```java
// Add field to track original gamemodes
private final Map<UUID, GameMode> originalGameMode = new ConcurrentHashMap<>();

// In applyVanishEffects():
private void applyVanishEffects(Player player) {
    UUID uuid = player.getUniqueId();
    
    // STORE original gamemode FIRST
    originalGameMode.put(uuid, player.getGameMode());
    
    // DO NOT change gamemode
    // ... rest of vanish logic ...
}

// In removeVanishEffects():
private void removeVanishEffects(Player player) {
    UUID uuid = player.getUniqueId();
    
    // RESTORE original gamemode
    GameMode original = originalGameMode.remove(uuid);
    if (original != null) {
        player.setGameMode(original);
    }
    
    // ... rest of unvanish logic ...
}
```

**Success Criteria:**
- Player gamemode unchanged by `/v` command
- Gamemode restored correctly on unvanish
- Works across all gamemode types (Survival, Creative, Adventure, Spectator)

---

## Issue 3: Help Command — Permission Display

### Problem
Help command shows "none" for permission instead of actual perm node.

### Root Cause
Likely in help command implementation where it displays permission but falls back to "none".

### Solution
**Find and Fix:**
1. Locate help command implementation (likely `VHelpCommand.java` or similar)
2. Find where it displays permissions
3. Change logic to display actual permission node instead of fallback
4. Verify shows: `vanishpp.vanish`, `vanishpp.admin`, etc.

**Files to Modify:**
- Help command file — likely in `commands/` directory

**Code Pattern:**
```java
// Instead of:
String perm = command.getPermission() != null ? command.getPermission() : "none";

// Do:
String perm = command.getPermission() != null ? command.getPermission() : "[no permission required]";
// Or better yet, ensure every command HAS a permission
```

**Success Criteria:**
- All commands display correct perm nodes
- No "none" text in help output

---

## Issue 4: Horse Interaction — Block Clicking

### Problem
Players can click/mount horses while vanished.

### Root Cause
Missing or incomplete player-entity interaction blocker. Likely needs to block `PlayerInteractEntityEvent`.

### Solution
**Find and Fix:**
1. Locate `PlayerInteractEntityEvent` handler
2. Add check: if player vanished AND entity is horse, cancel event
3. Also check for: mules, donkeys, llamas (horse-type entities)

**Files to Modify:**
- `EventListener.java` or similar event listener

**Code Pattern:**
```java
@EventHandler(priority = EventPriority.HIGHEST)
public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
    Player player = event.getPlayer();
    if (plugin.isVanished(player)) {
        Entity entity = event.getRightClicked();
        // Block interaction with horses, donkeys, mules, llamas, etc.
        if (entity instanceof AbstractHorse) {
            event.setCancelled(true);
        }
    }
}
```

**Success Criteria:**
- No horse interaction while vanished
- All horse types blocked (horse, donkey, mule, llama)

---

## Issue 5: Double Chest — Block Opening

### Problem
Single chests blocked, but double chests can be opened.

### Root Cause
Chest blocking logic only handles single chests, not both halves of double chest.

### Solution
**Find and Fix:**
1. Locate chest-blocking code (likely in `PlayerInteractEvent` or inventory event)
2. Current logic probably checks: `if (block.getType() == Material.CHEST)`
3. Change to also check for:
   - `Material.CHEST` (both halves)
   - Check if part of a double chest pair
   - Use `getRelative()` to find adjacent chest block
4. Block both halves

**Files to Modify:**
- `EventListener.java` — chest blocking section

**Code Pattern:**
```java
private boolean isPartOfDoubleChest(Block block) {
    if (block.getType() != Material.CHEST) return false;
    
    // Check all 4 adjacent blocks for another chest
    Block[] adjacent = {
        block.getRelative(BlockFace.NORTH),
        block.getRelative(BlockFace.SOUTH),
        block.getRelative(BlockFace.EAST),
        block.getRelative(BlockFace.WEST)
    };
    
    for (Block adj : adjacent) {
        if (adj.getType() == Material.CHEST) {
            return true;  // Part of double chest
        }
    }
    return false;
}

// In interact handler:
if (block.getType() == Material.CHEST || isPartOfDoubleChest(block)) {
    event.setCancelled(true);
}
```

**Success Criteria:**
- Single chest blocked
- Double chest (both halves) blocked
- Triple+ chest configurations blocked
- Chest animation/sound still plays (just blocked from opening)

---

## Issue 6: VAdmin GUI — Non-Boolean Value Handling

### Problem
GUI gets stuck when trying to adjust non-boolean config values. Toggle logic doesn't work for numerical values.

### Root Cause
GUI likely only has toggle/boolean logic, not numeric increment logic.

### Solution
**Find and Fix:**
1. Locate `VAdminCommand.java` or GUI handler
2. Find logic that handles inventory clicks
3. Current logic: probably just toggles true/false
4. New logic:
   - Detect value type (boolean vs numeric)
   - If boolean: toggle
   - If numeric: show adjustment interface
5. For numeric values:
   - Middle slot: displays current value
   - Left slot: minus button (−1)
   - Right slot: plus button (+1)
   - Shift+click: ±10
6. Enforce min/max bounds per config

**Files to Modify:**
- `commands/VAdminCommand.java` (main GUI logic)
- Config schema file (add min/max bounds per value)

**Code Pattern:**
```java
private void handleInventoryClick(InventoryClickEvent event, ConfigValue value) {
    if (value.getType() == ConfigType.BOOLEAN) {
        // Toggle logic
        toggleValue(value);
    } else if (value.getType() == ConfigType.NUMERIC) {
        // Adjustment logic
        int clickedSlot = event.getRawSlot();
        int currentValue = (int) value.getValue();
        int minValue = value.getMinBound();
        int maxValue = value.getMaxBound();
        
        if (clickedSlot == LEFT_BUTTON) {
            int decrement = event.isShiftClick() ? -10 : -1;
            adjustNumericValue(value, currentValue + decrement, minValue, maxValue);
        } else if (clickedSlot == RIGHT_BUTTON) {
            int increment = event.isShiftClick() ? +10 : +1;
            adjustNumericValue(value, currentValue + increment, minValue, maxValue);
        }
    }
}

private void adjustNumericValue(ConfigValue value, int newValue, int min, int max) {
    if (newValue < min || newValue > max) {
        // Play rejection sound
        playSound(SoundType.BOUNDARY_REACHED);
        return;
    }
    
    value.setValue(newValue);
    playSound(SoundType.SUCCESS);
    updateGUI();
}
```

**Config Schema Update:**
```yaml
vanish-delay-ticks:
  type: numeric
  default: 0
  min: 0
  max: 200
  description: "Delay ticks before vanish takes effect"

double-shift-window:
  type: numeric
  default: 150
  min: 50
  max: 1000
```

**Success Criteria:**
- No stuck GUI on numeric values
- ±1/±10 adjustment works smoothly
- Boundary sounds play on limit reached
- Values saved to config on change

---

## Issue 7: VAdmin GUI — Layout Redesign

### Problem
GUI layout is messy, categories mixed with settings. Page 2 is broken.

### Root Cause
Current implementation lacks proper categorization and page layout logic.

### Solution
**New Layout Structure:**
```
Row 0: [CAT1] [CAT2] [CAT3] [CAT4] [CAT5] ...  (Category tabs)
Row 1: (Empty — visual spacer)
Row 2+: Settings (wrapped, 2-indent if >7 items)
        Each setting takes up to 7 slots, then wraps
        Each category has visual indicator
```

**Implementation:**
1. Reserve Row 0 for category tabs
2. Each category = 1 item in Row 0
3. Click category item to switch category
4. Settings for that category displayed below
5. If >7 settings, wrap to next row with 2-slot indent
6. Use consistent materials for visual hierarchy

**Files to Modify:**
- `commands/VAdminCommand.java` — entire GUI rendering logic
- May create separate `ConfigGUIManager.java` for cleaner code

**Pseudo-Code:**
```java
private void renderCategoryGUI(String category, int page) {
    Inventory gui = Bukkit.createInventory(null, 54, "Vanish++ Config — " + category);
    
    // Row 0: Categories
    int catSlot = 0;
    for (String cat : allCategories) {
        Material catMaterial = category.equals(cat) ? 
            Material.YELLOW_STAINED_GLASS : Material.BLUE_STAINED_GLASS;
        ItemStack catItem = createCategoryItem(cat, catMaterial);
        gui.setItem(catSlot++, catItem);
    }
    
    // Row 1: Empty (spacer)
    // Rows 2+: Settings (wrapped)
    List<ConfigValue> settingsForCategory = getSettings(category);
    int settingSlot = 9 + 9;  // Start at Row 2
    int colPosition = 0;      // 0-6 positions per row
    
    for (ConfigValue setting : settingsForCategory) {
        if (colPosition > 6) {
            settingSlot += 2 + 7;  // Skip row, add 2-indent
            colPosition = 0;
        }
        
        ItemStack settingItem = createSettingItem(setting);
        gui.setItem(settingSlot++, settingItem);
        colPosition++;
    }
    
    player.openInventory(gui);
}
```

**Success Criteria:**
- Categories clearly visible in Row 0
- Settings organized and wrapped
- Visual hierarchy clear
- All pages functional
- No layout overflow

---

## Issue 8: VAdmin Page 2 — Interaction Bug

### Problem
Page 2 items are clickable/draggable but don't perform actions.

### Root Cause
Event listener for page 2 either:
- Not registered for page 2
- Missing click detection logic
- Dragging interferes with clicking

### Solution
**Find and Fix:**
1. Locate inventory click handler
2. Add page detection logic
3. Ensure click events fire for all pages
4. Add drag detection to prevent drag interfering with click
5. Only allow drag on decorative items, not settings

**Files to Modify:**
- `commands/VAdminCommand.java` — click event handler

**Code Pattern:**
```java
@EventHandler
public void onInventoryClick(InventoryClickEvent event) {
    if (!isVanishppConfigGUI(event.getInventory())) return;
    
    event.setCancelled(true);  // Prevent default behavior
    
    Player player = (Player) event.getWhoClicked();
    int slot = event.getRawSlot();
    int page = detectPage(event.getInventory());  // Track which page
    
    // Only allow drag on empty/decorative slots
    if (event.isShiftClick() && !isDecorative(slot)) {
        return;  // Prevent drag on settings
    }
    
    ConfigValue value = getValueAtSlot(slot, page);
    if (value != null) {
        handleValueClick(player, value, slot, event);
    }
}
```

**Success Criteria:**
- All pages fully functional
- Clicks register on all pages
- Dragging doesn't interfere
- Settings respond to clicks on all pages

---

## Implementation Order

### Phase 1 — Core Blockers (3 hours)
1. **Mob Targeting** — highest priority, affects core invisibility
2. **Spectator Mode** — critical gamemode bug
3. **Double Chest** — gameplay blocking

### Phase 2 — Minor Issues (1 hour)
4. **Horse Interaction** — minor but easy
5. **Help Permissions** — minor display fix

### Phase 3 — GUI Overhaul (4-5 hours)
6. **Non-Boolean Handling** — major refactor
7. **Layout Redesign** — major refactor
8. **Page 2 Fix** — part of layout redesign

---

## Testing After Each Phase

| Phase | Test | Sign-Off |
|-------|------|----------|
| 1 | Mob targeting, spectator, chests | [ ] |
| 2 | Horse, help perms | [ ] |
| 3 | GUI all values, layout, pages | [ ] |
| Final | Full client testing checklist | [ ] |

---

## Sign-Off Before Implementation

**Status:** Ready for implementation?

- [ ] Plan reviewed
- [ ] All 8 issues understood
- [ ] Estimation accurate
- [ ] Prioritization correct

**Any changes needed before we start?**

