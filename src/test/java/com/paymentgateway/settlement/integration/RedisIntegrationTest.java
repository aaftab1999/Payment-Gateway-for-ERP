package com.paymentgateway.settlement.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: verifies Redis connectivity using Jedis directly.
 *
 * <p>This test does not load the Spring context — it verifies that
 * a Redis container is reachable on the mapped port. Requires Docker.</p>
 */
@Testcontainers
class RedisIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private static JedisPool pool;

    @BeforeAll
    static void setUp() {
        String host = REDIS.getHost();
        Integer port = REDIS.getMappedPort(6379);
        pool = new JedisPool(host, port);
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    void redisPingAndWriteRead() {
        try (Jedis jedis = pool.getResource()) {
            // PING
            assertThat(jedis.ping()).isEqualTo("PONG");

            // SET/GET
            jedis.set("healthcheck:test", "ok");
            assertThat(jedis.get("healthcheck:test")).isEqualTo("ok");

            // DEL to clean up
            jedis.del("healthcheck:test");
        }
    }
}
