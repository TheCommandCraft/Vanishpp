# Testing Checklist — Bug Fixes for Client Release

**Priority:** CRITICAL — Client-blocking issues  
**Target:** v1.1.9 stable release  
**Date:** 2026-05-29

---

## 1. Mob Targeting & Looking (CRITICAL)

### Baby Zombie Look-At Bug
**Status:** NOT FIXED — Mobs still look at vanished player  
**Test Steps:**
1. Run `/v` to vanish
2. Spawn baby zombies: `/summon zombie ~ ~ ~ {IsBaby:1b}`
3. Verify: Zombies should NOT look at you, NOT turn heads toward you
4. Verify: Adult zombies also don't look
5. Test other mobs: creepers, skeletons, spiders

**Expected:** Zero mob head rotation toward vanished player  
**Current:** Mobs are looking/tracking  
**Acceptance:** Mobs completely ignore vanished player in all aspects

---

## 2. Gamemode Handling (CRITICAL)

### Spectator Mode on Vanish
**Status:** BROKEN — Player forced to Spectator on `/v`  
**Test Steps:**
1. Set gamemode to Survival: `/gamemode survival`
2. Verify you're in Survival (can break blocks, etc.)
3. Run `/v`
4. Verify: Gamemode should REMAIN Survival (not change to Spectator)
5. Run `/v` again to unvanish
6. Verify: Gamemode still Survival

**Expected:** Gamemode unchanged when vanishing  
**Current:** Player put into Spectator mode  
**Acceptance Criteria:**
- `/v` does NOT alter gamemode
- Player stays in original gamemode during vanish
- Unvanish restores same gamemode

---

## 3. Help Command Permissions (MINOR)

**Status:** BROKEN — Shows "none" instead of actual permission  
**Test Steps:**
1. Run `/vhelp`
2. Look at each command's permission display
3. Verify: Should show actual permission nodes (e.g., `vanishpp.vanish`, `vanishpp.admin`)
4. Verify: Not showing "none" or blank

**Expected:** Real permission nodes displayed  
**Current:** Shows "none"  
**Acceptance:** Each command displays correct perm node

---

## 4. Horse Interaction (MINOR)

**Status:** BROKEN — Can still interact with horses  
**Test Steps:**
1. Run `/v` to vanish
2. Spawn horse: `/summon horse`
3. Try to click/mount the horse
4. Verify: Horse interaction is BLOCKED (can't mount, can't feed, etc.)

**Expected:** Horse interaction blocked  
**Current:** Can still click horses  
**Acceptance:** No horse interaction allowed while vanished

---

## 5. Double Chest Block (MINOR)

**Status:** BROKEN — Single chests blocked, double chests work  
**Test Steps:**
1. Run `/v` to vanish
2. Place single chest, try to open: `/setblock ~ ~ ~ chest`
3. Verify: Single chest BLOCKED
4. Place double chest, try to open
5. Verify: Double chest also BLOCKED
6. Verify: Sound/animation still plays (just blocked from opening)

**Expected:** Both single and double chests blocked  
**Current:** Double chests allow opening  
**Acceptance:** No chest type can be opened while vanished

---

## 6. VAdmin Config GUI — Non-Boolean Values (CRITICAL)

**Status:** BROKEN — Config GUI stuck on non-boolean toggles  
**Test Steps:**
1. Open VAdmin config GUI
2. Find a numerical config value (e.g., `vanish-delay-ticks`, `double-shift-window`)
3. Try to toggle/change it
4. Verify: GUI should open value selection interface (not stuck)
5. Click +/- buttons to adjust
6. Verify: Sound plays on successful change
7. Verify: Sound plays on boundary reached (can't go lower/higher)

**Expected:** Smooth numerical value adjustment with UI feedback  
**Current:** GUI gets stuck, no way to adjust non-boolean values  
**Acceptance:** All value types adjustable in GUI

---

## 7. VAdmin GUI Layout Redesign (CRITICAL)

**Status:** NOT STARTED — Major UI overhaul needed  
**Test Steps:**
1. Open VAdmin config GUI
2. Verify layout:
   - [ ] Row 0: Category tabs (organized left-to-right)
   - [ ] Row 1: Empty/spacer
   - [ ] Rows 2+: Settings (wrapped, 2-indent if longer than 7 items)
   - [ ] Each category clearly separated
3. For numerical values:
   - [ ] Center: displays current value
   - [ ] Left: minus button (−1, −10)
   - [ ] Right: plus button (+1, +10)
4. Verify: No values go outside configured ranges
5. Verify: Sound feedback on value change
6. Verify: Different sound on boundary rejection

**Expected:** Professional, organized GUI with full functionality  
**Current:** Categories mixed up, page 2 broken  
**Acceptance:** Multi-page GUI fully functional and intuitive

---

## 8. VAdmin Page 2 Interaction Bug (MINOR)

**Status:** BROKEN — Page 2 items clickable/draggable but don't work  
**Test Steps:**
1. Navigate to page 2 in VAdmin
2. Try to click an item
3. Verify: Item should perform action (not just drag)
4. Verify: Dragging doesn't interfere with clicking

**Expected:** Page 2 items fully functional  
**Current:** Items are interactive but don't work  
**Acceptance:** All pages work identically

---

## Testing Order (Before Release)

### Phase 1 — Core Issues (Blocker)
- [ ] Mob targeting (zombies not looking)
- [ ] Spectator gamemode (should NOT change)

### Phase 2 — Interaction Issues
- [ ] Horse clicking blocked
- [ ] Double chest blocked
- [ ] Help perms display correct

### Phase 3 — GUI Overhaul
- [ ] VAdmin layout redesigned (categories, wrapping)
- [ ] Numerical value adjustment (±1, ±10 buttons)
- [ ] Range limits enforced with sound feedback
- [ ] Page 2 fully functional

---

## Sign-Off

**Tester:** [your name]  
**Date Tested:** ___________  
**All Issues Fixed:** [ ] YES  [ ] NO  
**Notes:**
```
[Add any additional findings here]
```

