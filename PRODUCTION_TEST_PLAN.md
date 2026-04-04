# Vanish++ v1.1.6 — Production Testing Plan

## Pre-Release Validation (Admin-Level Testing)

### 1. Database Persistence Testing

#### MySQL
```bash
# Scenario: Verify vanish state persists across restarts

1. Deploy to Paper server with MySQL storage
   /vanish                    # Vanish as admin
   ## Verify in MySQL: mysql -u root -e "SELECT * FROM vanishpp.vpp_vanished"
   
2. Restart Paper server
   /mcrcon stop             # Clean shutdown via RCON
   # Wait 5 seconds
   # Restart server
   
3. Verify vanish state restored on join
   ## Player should automatically apply vanish effects
   ## Check logs for: "Folia environment detected" OR "Standard Bukkit/Paper environment"
```

#### PostgreSQL
```bash
# Same as MySQL but with PostgreSQL config:
# storage.type: "POSTGRESQL"
# storage.mysql.port: 5432
# storage.mysql.database: vanishpp
```

#### Cross-Database Sync
```bash
# Scenario: Test migration path from YAML → MySQL → PostgreSQL

1. Start with YAML storage, vanish players (stored in data.yml)
2. Stop server, update config to MySQL, restart
   ## Logs should show: "SQL schema migrated to v2"
3. Verify same players still vanished
4. Repeat with PostgreSQL
```

### 2. Folia Multi-Region Testing

```bash
# Scenario: Ensure Folia handles multi-region player visibility correctly

1. Deploy to Folia server (see docker/docker-compose.yml for test instance)
2. Place vanished staff player in one region (e.g., region 0,0)
3. Place normal player in different region (e.g., region 1,1)
4. Teleport normal player to region where vanished staff is
   ## Normal player should NOT see the staff
   ## Check logs: "Folia environment detected. Using Regional Scheduler."
5. Give normal player vanishpp.see permission
6. Teleport back
   ## Normal player should NOW see staff
7. Repeat across 4+ regions to stress-test multi-region scheduler
```

### 3. Redis Cross-Server Sync Testing

```bash
# Scenario: Test real-time sync between two Paper servers on same Redis

Setup: 2 Paper servers + 1 Redis
  Paper 1 (localhost:25565)
  Paper 2 (localhost:25566)
  Redis   (localhost:6379)

1. Configure both servers:
   storage.redis.enabled: true
   storage.redis.host: localhost
   storage.redis.port: 6379

2. Start both servers
   ## Check logs: "Redis synchronization initialized"

3. Vanish player on Server 1
   /vanish
   ## Server 1 logs: Broadcasting to Redis
   ## Server 2 logs: "Redis Subscriber received VANISH:UUID"

4. Player joins Server 2
   ## Should be automatically vanished (from Redis message)
   
5. Unvanish on Server 1
   /unvanish
   ## Server 2 should automatically unvanish the player

6. Test idempotency: Stop Server 2, spam Redis messages on Server 1
   /vanish && /unvanish && /vanish && /vanish
   ## Restart Server 2
   ## Check final state is consistent (last /vanish command wins)
```

### 4. Database Connection Loss Handling

```bash
# Scenario: Test admin notification when database goes down

1. Server running with MySQL/PostgreSQL storage
   /vanish                   # Vanish successfully
   
2. Simulate DB connection loss:
   mysql> FLUSH TABLES       # Blocks access
   OR
   Kill all connections to the database
   
3. Try /vanish on another player
   ## ERROR should appear in console/logs
   ## Admins in-game should see: "[Vanish++] Database connection failed!"
   ## Second error within 5 minutes should NOT spam (cooldown enforced)
   
4. Restore database connection
   mysql> UNLOCK TABLES
   
5. /vanish should work again
   ## No errors in logs
```

### 5. Large Server Load Testing (100+ Concurrent Players)

```bash
# Scenario: Test visibility sync performance with many vanished players

1. Load test with 100+ online players
   20 of them vanished (20% vanished ratio)
   
2. Monitor CPU/memory:
   - Vanish commands should execute <50ms
   - Visibility sync task (runs every 10 ticks) should complete <5ms
   - Check logs for timing info (add with /vanishconfig if needed)
   
3. Check for lag:
   /tps command should stay above 18.0
   
4. Verify no visibility bugs:
   Normal players see correct visibility
   Staff see all players
   
5. Performance regression test:
   Add 100+ more players
   Performance should degrade linearly (O(n)), not quadratically (O(n²))
```

### 6. Rule Cache Preload Testing

```bash
# Scenario: Ensure rules are loaded correctly on join

1. Set custom rule for a player:
   /vrules can_break_blocks false
   
2. Player logs off
3. Server restarts
4. Player logs back in:
   ## Should immediately NOT be able to break blocks
   ## No 1-2 tick delay allowed
   
5. Check logs for: "Preloading rules for UUID"
```

### 7. PostgreSQL Schema Migration Testing

```bash
# Scenario: Test idempotent schema migrations

1. First startup:
   ## Logs: "SQL schema migrated to v2"
   
2. Stop and restart server:
   ## Logs: "SQL schema migrated to v2" (idempotent, no errors)
   ## Should NOT try to re-create columns
   
3. Query PostgreSQL:
   \d vpp_vanished
   ## Should have: uuid (PK), created_at
   
   \d vpp_rules
   ## Should have: uuid, rule_key, rule_value, updated_at
```

### 8. Network Sync Idempotency Testing

```bash
# Scenario: Simulate duplicate Redis messages (network flakiness)

Tools needed: redis-cli

1. Manual Redis message injection:
   redis-cli PUBLISH vanishpp:sync "VANISH:550e8400-e29b-41d4-a716-446655440001"
   redis-cli PUBLISH vanishpp:sync "VANISH:550e8400-e29b-41d4-a716-446655440001"
   redis-cli PUBLISH vanishpp:sync "VANISH:550e8400-e29b-41d4-a716-446655440001"
   
   ## Server should log:
   "Ignoring duplicate network vanish sync for UUID (already vanished)"
   
2. Verify final state is correct (one entry in vpp_vanished, not three)
```

## Test Execution Checklist

```
[ ] MySQL persistence across restarts (3+ restarts)
[ ] PostgreSQL persistence across restarts (3+ restarts)  
[ ] YAML → MySQL migration
[ ] YAML → PostgreSQL migration
[ ] Folia multi-region visibility (4+ regions)
[ ] Redis sync between servers (2+ servers)
[ ] Database connection loss notification
[ ] Large server load test (100+ players)
[ ] Rule cache preload on join
[ ] PostgreSQL schema v2 migration
[ ] Network sync idempotency

Total: 11 test categories
Estimated time: 2-3 hours per platform (Paper/Folia)
Platforms to test: Paper 1.21.11, Folia (latest)
```

## Success Criteria

✅ **All tests pass** without exceptions
✅ **No data loss** across restart cycles
✅ **Admins are notified** of critical issues
✅ **No ConcurrentModificationException** logs
✅ **TPS remains >15** even with 100+ players and 20% vanished
✅ **Visibility is instant** (no 1-tick delays)
✅ **Redis is gracefully handled** (clean shutdown, proper reconnect)

## Regression Testing (Compare to v1.1.5)

After deploying v1.1.6, compare:
- Startup time (should be same or faster)
- Memory usage (should be same or less)
- Database query count (should be same or fewer)
- Error logs (should be fewer)
- CPU per vanished player (should be same)

---

## Deployment Steps

```bash
# 1. Build final JAR
mvn clean package -DskipTests -q

# 2. Backup current database
mysqldump -u root vanishpp > backup_1.1.6.sql
pg_dump vanishpp > backup_1.1.6.sql  # PostgreSQL

# 3. Deploy JAR to production servers
cp target/vanishpp-1.1.6.jar /path/to/plugins/

# 4. Restart servers (clean shutdown via RCON)
mcrcon stop

# 5. Monitor logs for issues
tail -f logs/latest.log

# 6. Run smoke tests (player joins, vanish commands, admin notifications)
# 7. Monitor TPS, memory, CPU for 30 minutes

# 8. If all good: Release publicly on Modrinth
```

---

## Known Limitations (Documented)

1. **Scoreboard teams on Folia**: Nametag prefix features disabled (UnsupportedOperationException) — gracefully degrades
2. **PostgreSQL transaction safety**: Relies on JDBC transaction support; some edge cases with concurrent access possible
3. **Large server visibility sync**: O(n²) algorithm visible on 500+ player servers; acceptable, not critical for 1.1.6

---

**Last Updated**: 2026-04-04
**Target Version**: 1.1.6 (Beta → Release)
**Test Status**: Ready for production validation
