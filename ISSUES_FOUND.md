# Critical Issues Found in Vanish++ v1.1.6

## Database & Storage Issues

### 1. **CRITICAL: SqlStorage.removePlayerData() Incomplete**
- **Location**: SqlStorage.java:272-289
- **Issue**: Only deletes from vpp_rules, vpp_acknowledgements, and vpp_levels. Does NOT delete from vpp_vanished.
- **Impact**: When removePlayerData() is called, the player remains marked as vanished in the database forever.
- **Affects**: MySQL, PostgreSQL
- **Severity**: CRITICAL - Data leak, causes unintended persistence

### 2. **CRITICAL: Missing Transaction in removePlayerData()**
- **Location**: SqlStorage.java:272-289
- **Issue**: Three separate DELETE statements without transaction wrapper. If one fails, others may succeed, leaving inconsistent state.
- **Impact**: Database corruption on connection errors or timeouts
- **Affects**: MySQL, PostgreSQL
- **Severity**: CRITICAL - Data integrity

### 3. **HIGH: PostgreSQL addAcknowledgement() Syntax Error**
- **Location**: SqlStorage.java:227
- **Issue**: `ON CONFLICT DO NOTHING` is incomplete. Should be `ON CONFLICT (uuid, notification_id) DO NOTHING`
- **Impact**: Duplicate key errors on re-adding acknowledgements
- **Affects**: PostgreSQL only
- **Severity**: HIGH - Prevents some operations from completing

## Multi-Server & Proxy Sync Issues

### 4. **HIGH: Redis Subscriber Thread Not Gracefully Terminated**
- **Location**: RedisStorage.java:55-59
- **Issue**: pubSubThread is daemon but shutdown() doesn't interrupt it. No waiting for graceful termination.
- **Impact**: Thread lingers, consuming resources. Unprocessed sync messages lost on reload.
- **Affects**: Any setup using Redis for cross-server sync
- **Severity**: HIGH - Resource leak, missed sync events

### 5. **MEDIUM: Race Condition in Network Sync**
- **Location**: Vanishpp.java:331-348 (handleNetworkVanishSync)
- **Issue**: If Redis/proxy sends identical sync message twice, both are applied. No idempotency check.
- **Impact**: Visibility state could diverge if network flakiness causes duplicate broadcasts
- **Affects**: Cross-server setups with Redis enabled
- **Severity**: MEDIUM - Rare but causes visibility bugs

## Performance & Concurrency Issues

### 6. **MEDIUM: O(n²) Visibility Sync in startSyncTask()**
- **Location**: Vanishpp.java:542-574
- **Issue**: For each vanished player, loops ALL online players. With 500 players and 100 vanished = 50,000 iterations per sync cycle (every 10 ticks = 2x per second)
- **Impact**: CPU spikes, especially on Folia with multi-region overhead
- **Affects**: All large servers (100+ concurrent players)
- **Severity**: MEDIUM - Scales poorly, but detectable on large servers

### 7. **MEDIUM: Rule Cache Preload Race Condition**
- **Location**: RuleManager.java (from prior work) and PlayerListener.java:99-107
- **Issue**: Player joins, ruleManager.preloadRules() runs async. Meanwhile, rules.getRule() called synchronously could load stale value from DB instead of waiting for async preload.
- **Impact**: Player gets default rule values first 1-2 ticks, then correct ones later
- **Affects**: All servers with rule-based restrictions
- **Severity**: MEDIUM - Brief inconsistency on join, usually unnoticed

## Folia-Specific Issues

### 8. **MEDIUM: Folia Visibility Desync on Respawn**
- **Location**: Vanishpp.java:776-787 (updateVanishVisibility)
- **Issue**: Called synchronously from different regions. If player teleports between regions (region change forces respawn in Folia), visibility packets may be sent before entity respawn is complete in target region.
- **Impact**: Client-side desync: player appears, then disappears, then reappears
- **Affects**: Folia only
- **Severity**: MEDIUM - Cosmetic but noticeable

## Production Release Issues

### 9. **Configuration Migration Incomplete**
- **Location**: SqlStorage.java:73-88 (runSchemaMigrations)
- **Issue**: Schema version system exists but no migration logic for future schema changes. Adding new columns breaks existing databases.
- **Impact**: Future updates will require manual database schema modifications
- **Affects**: MySQL, PostgreSQL
- **Severity**: MEDIUM - Blocks automated updates post-1.1.6

### 10. **No Graceful Degradation on DB Connection Loss**
- **Location**: SqlStorage.java (all methods)
- **Issue**: Every DB operation catches SQLException and logs, but returns default values. No retry logic. If DB goes down mid-operation, no notification to staff/players.
- **Impact**: Silent vanish state corruption if DB temporarily unavailable
- **Affects**: All SQL databases
- **Severity**: MEDIUM-HIGH - Production stability

## Summary
- **CRITICAL (2)**: removePlayerData incompleteness, transaction safety
- **HIGH (2)**: PostgreSQL syntax, Redis thread termination
- **MEDIUM (6)**: Performance, Folia concurrency, race conditions, missing migrations

## Recommended Action Order
1. Fix removePlayerData() deletion + transactions
2. Fix PostgreSQL syntax error
3. Add Redis thread interruption on shutdown
4. Implement idempotency check in network sync
5. Optimize visibility sync O(n²) to O(n)
6. Add schema migration framework
7. Implement retry logic with staff notifications
