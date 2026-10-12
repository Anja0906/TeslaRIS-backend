package rs.teslaris.core.unit.coarnotify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import rs.teslaris.core.service.impl.coarnotify.TransferTokenServiceImpl;

public class TransferTokenServiceTest {

    private static final Instant EXPIRES = Instant.ofEpochSecond(1_789_770_000L);

    private final TransferTokenServiceImpl transferTokenService =
        new TransferTokenServiceImpl("0123456789abcdef0123456789abcdef");


    @Test
    public void shouldMatchTokenMintedWithSameParameters() {
        var token = transferTokenService.mint(7, "abc.pdf", EXPIRES);

        assertEquals(64, token.length());
        assertTrue(transferTokenService.matches(token, 7, "abc.pdf", EXPIRES));
    }

    @Test
    public void shouldNotMatchOtherTransferFileOrExpiry() {
        var token = transferTokenService.mint(7, "abc.pdf", EXPIRES);

        assertFalse(transferTokenService.matches(token, 8, "abc.pdf", EXPIRES));
        assertFalse(transferTokenService.matches(token, 7, "other.pdf", EXPIRES));
        assertFalse(transferTokenService.matches(token, 7, "abc.pdf", EXPIRES.plusSeconds(1)));
    }

    @Test
    public void shouldNotMatchMissingOrMalformedToken() {
        assertFalse(transferTokenService.matches(null, 7, "abc.pdf", EXPIRES));
        assertFalse(transferTokenService.matches("not-hex", 7, "abc.pdf", EXPIRES));
        assertFalse(transferTokenService.matches("00", 7, "abc.pdf", EXPIRES));
    }

    @Test
    public void shouldMintDifferentTokensForDifferentSecrets() {
        var otherService = new TransferTokenServiceImpl("another-secret-another-secret-xx");
        var token = transferTokenService.mint(7, "abc.pdf", EXPIRES);

        assertNotEquals(token, otherService.mint(7, "abc.pdf", EXPIRES));
        assertFalse(otherService.matches(token, 7, "abc.pdf", EXPIRES));
    }

    @Test
    public void shouldUseRandomSecretWhenNoneIsConfigured() {
        var first = new TransferTokenServiceImpl("");
        var second = new TransferTokenServiceImpl("");
        var token = first.mint(1, "f.pdf", EXPIRES);

        assertTrue(first.matches(token, 1, "f.pdf", EXPIRES));
        assertFalse(second.matches(token, 1, "f.pdf", EXPIRES));
    }
}
