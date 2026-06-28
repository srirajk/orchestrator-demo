package ai.meridian.gateway.domain.auth;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.JedisPooled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevocationCheckerTest {

    @Mock JedisPooled jedis;

    @Test
    void revocationKeyPresent_returnsTrue() {
        var checker = new RevocationChecker(jedis, new SimpleMeterRegistry());
        when(jedis.exists("revocation:rm_jane:REL-00188")).thenReturn(true);

        assertThat(checker.isRevoked("rm_jane", "REL-00188")).isTrue();
    }

    @Test
    void revocationKeyAbsent_returnsFalse() {
        var checker = new RevocationChecker(jedis, new SimpleMeterRegistry());
        when(jedis.exists("revocation:rm_jane:REL-00042")).thenReturn(false);

        assertThat(checker.isRevoked("rm_jane", "REL-00042")).isFalse();
    }

    @Test
    void nullUserId_returnsFalse() {
        var checker = new RevocationChecker(jedis, new SimpleMeterRegistry());
        assertThat(checker.isRevoked(null, "REL-00042")).isFalse();
    }

    @Test
    void nullRelId_returnsFalse() {
        var checker = new RevocationChecker(jedis, new SimpleMeterRegistry());
        assertThat(checker.isRevoked("rm_jane", null)).isFalse();
    }

    @Test
    void redisException_failsOpen_returnsFalse() {
        var checker = new RevocationChecker(jedis, new SimpleMeterRegistry());
        when(jedis.exists("revocation:rm_jane:REL-00042")).thenThrow(new RuntimeException("Redis down"));

        assertThat(checker.isRevoked("rm_jane", "REL-00042")).isFalse();
    }
}
