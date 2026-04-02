package net.thecommandcraft.vanishpp.storage;

import net.thecommandcraft.vanishpp.Vanishpp;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;

public class RedisStorage {

    private final Vanishpp plugin;
    private JedisPool jedisPool;
    private String channel = "vanishpp:sync";
    private Thread pubSubThread;

    public RedisStorage(Vanishpp plugin) {
        this.plugin = plugin;
    }

    public void init() {
        String host = plugin.getConfig().getString("storage.redis.host", "localhost");
        int port = plugin.getConfig().getInt("storage.redis.port", 6379);
        String password = plugin.getConfig().getString("storage.redis.password", "");

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);

        if (password == null || password.isEmpty()) {
            this.jedisPool = new JedisPool(poolConfig, host, port);
        } else {
            this.jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
        }

        // Start Subscriber Thread
        this.pubSubThread = new Thread(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleSyncMessage(message);
                    }
                }, channel);
            } catch (Exception e) {
                plugin.getLogger().warning("Redis Subscriber disconnected: " + e.getMessage());
            }
        }, "Vanishpp-Redis-Subscriber");
        this.pubSubThread.setDaemon(true);
        this.pubSubThread.start();

        plugin.getLogger().info("Redis synchronization initialized.");
    }

    public void shutdown() {
        if (jedisPool != null) {
            jedisPool.close();
        }
    }

    public void broadcastVanish(UUID uuid, boolean vanished) {
        String payload = (vanished ? "VANISH:" : "UNVANISH:") + uuid.toString();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.publish(channel, payload);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to publish Redis sync: " + e.getMessage());
        }
    }

    void handleSyncMessage(String message) {
        try {
            String[] parts = message.split(":");
            if (parts.length != 2)
                return;

            String action = parts[0];
            UUID uuid = UUID.fromString(parts[1]);

            // Sync with local memory / visibility logic
            // We use a task to ensure it runs on the main Bukkit thread
            plugin.getVanishScheduler().runGlobal(() -> {
                plugin.handleNetworkVanishSync(uuid, action.equals("VANISH"));
            });
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to process Redis sync message: " + e.getMessage());
        }
    }
}
