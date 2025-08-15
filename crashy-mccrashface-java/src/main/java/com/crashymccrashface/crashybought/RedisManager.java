package com.crashymccrashface.crashybought;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisManager {

    private final JedisPool pool;

    public RedisManager(Config config) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10);
        poolConfig.setMaxIdle(5);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);

        if (config.getRedisPassword() != null && !config.getRedisPassword().isEmpty()) {
            pool = new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(), 2000, config.getRedisPassword(), config.isRedisSsl());
        } else {
            pool = new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(), 2000, null, config.isRedisSsl());
        }
    }

    public Jedis getJedis() {
        return pool.getResource();
    }

    public void close() {
        pool.close();
    }
}
