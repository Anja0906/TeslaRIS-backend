package rs.teslaris.core.unit.coarnotify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rs.teslaris.core.model.coarnotify.FileTransferStatus.ACCEPTED;
import static rs.teslaris.core.model.coarnotify.FileTransferStatus.FAILED;
import static rs.teslaris.core.model.coarnotify.FileTransferStatus.PENDING;
import static rs.teslaris.core.model.coarnotify.FileTransferStatus.REJECTED;
import static rs.teslaris.core.model.coarnotify.FileTransferStatus.SENT;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import rs.teslaris.core.model.coarnotify.FileTransfer;
import rs.teslaris.core.model.coarnotify.FileTransferStatus;
import rs.teslaris.core.util.coarnotify.TransferRetryPolicy;

public class TransferRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");

    private static final Duration BACKOFF_BASE = Duration.ofSeconds(60);

    private static final Duration ACK_TIMEOUT = Duration.ofMinutes(30);

    private static final int MAX_ATTEMPTS = 5;


    @Test
    public void shouldWaitForDoublingBackoffAfterFailedOffer() {
        assertFalse(isDue(createTransfer(FAILED, 1, Duration.ofSeconds(59), "x")));
        assertTrue(isDue(createTransfer(FAILED, 1, Duration.ofSeconds(60), "x")));
        assertFalse(isDue(createTransfer(FAILED, 2, Duration.ofSeconds(119), "x")));
        assertTrue(isDue(createTransfer(FAILED, 2, Duration.ofSeconds(120), "x")));
        assertFalse(isDue(createTransfer(FAILED, 3, Duration.ofSeconds(239), "x")));
        assertTrue(isDue(createTransfer(FAILED, 3, Duration.ofSeconds(240), "x")));
    }

    @Test
    public void shouldStopAfterMaxAttempts() {
        assertTrue(isDue(createTransfer(FAILED, 4, Duration.ofDays(1), "x")));
        assertFalse(isDue(createTransfer(FAILED, 5, Duration.ofDays(1), "x")));
    }

    @Test
    public void shouldRetryOnlyFixableRejects() {
        var longAgo = Duration.ofHours(2);

        assertTrue(isDue(createTransfer(REJECTED, 1, longAgo,
            "expired: item URL returned HTTP 410")));
        assertTrue(isDue(createTransfer(REJECTED, 1, longAgo,
            "fetch-failed: SocketTimeoutException: Read timed out")));
        assertTrue(isDue(createTransfer(REJECTED, 1, longAgo,
            "digest-mismatch: expected sha-256 AAAA")));
        assertFalse(
            isDue(createTransfer(REJECTED, 1, longAgo, "url-rejected: x")));
        assertFalse(isDue(createTransfer(REJECTED, 1, longAgo, "too-large: x")));
        assertFalse(
            isDue(createTransfer(REJECTED, 1, longAgo, "ingest-failed: x")));
        assertFalse(
            isDue(createTransfer(REJECTED, 1, longAgo, "digest-missing: x")));
        assertFalse(isDue(createTransfer(REJECTED, 1, longAgo, null)));
        assertFalse(isDue(
            createTransfer(REJECTED, 1, Duration.ofSeconds(10), "expired: x")));
    }

    @Test
    public void shouldRetryUnansweredOfferAfterAckTimeout() {
        assertFalse(isDue(createTransfer(SENT, 1, Duration.ofMinutes(29), null)));
        assertTrue(isDue(createTransfer(SENT, 1, Duration.ofMinutes(30), null)));
        assertTrue(
            isDue(createTransfer(PENDING, 1, Duration.ofMinutes(31), null)));
    }

    @Test
    public void shouldNeverRetryAcceptedTransfer() {
        assertFalse(isDue(createTransfer(ACCEPTED, 1, Duration.ofDays(3), null)));
    }

    @Test
    public void shouldReadReasonCodeFromSummary() {
        assertEquals("expired",
            TransferRetryPolicy.getReasonCode("expired: item URL returned HTTP 410"));
        assertEquals("digest-mismatch", TransferRetryPolicy.getReasonCode("Digest-Mismatch : x"));
        assertEquals("no colon", TransferRetryPolicy.getReasonCode("no colon"));
        assertEquals("", TransferRetryPolicy.getReasonCode(null));
    }

    private boolean isDue(FileTransfer transfer) {
        return TransferRetryPolicy.isDue(transfer, NOW, MAX_ATTEMPTS, BACKOFF_BASE, ACK_TIMEOUT);
    }

    private FileTransfer createTransfer(FileTransferStatus status, int attempts,
                                        Duration sinceLastAttempt, String lastError) {
        var transfer = new FileTransfer();
        transfer.setStatus(status);
        transfer.setAttempts(attempts);
        transfer.setLastAttemptAt(
            Objects.isNull(sinceLastAttempt) ? null : NOW.minus(sinceLastAttempt));
        transfer.setLastError(lastError);
        return transfer;
    }
}
