package rs.teslaris.core.util.coarnotify;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import rs.teslaris.core.model.coarnotify.FileTransfer;

public class TransferRetryPolicy {

    private static final Set<String> RETRYABLE_REJECT_REASONS =
        Set.of("expired", "fetch-failed", "digest-mismatch");

    private static final Duration MAX_BACKOFF = Duration.ofDays(1);


    public static boolean isDue(FileTransfer transfer, Instant now, int maxAttempts,
                                Duration backoffBase, Duration ackTimeout) {
        if (transfer.getAttempts() >= maxAttempts) {
            return false;
        }

        var lastAttempt = transfer.getLastAttemptAt();
        return switch (transfer.getStatus()) {
            case ACCEPTED -> false;
            case FAILED -> isBackoffOver(lastAttempt, now, transfer.getAttempts(), backoffBase);
            case REJECTED ->
                RETRYABLE_REJECT_REASONS.contains(getReasonCode(transfer.getLastError())) &&
                    isBackoffOver(lastAttempt, now, transfer.getAttempts(), backoffBase);
            case PENDING, SENT ->
                Objects.isNull(lastAttempt) || !now.isBefore(lastAttempt.plus(ackTimeout));
        };
    }

    public static String getReasonCode(String summary) {
        if (Objects.isNull(summary)) {
            return "";
        }

        var colon = summary.indexOf(':');
        return (colon < 0 ? summary : summary.substring(0, colon)).trim().toLowerCase();
    }

    private static boolean isBackoffOver(Instant lastAttempt, Instant now, int attempts,
                                         Duration backoffBase) {
        if (Objects.isNull(lastAttempt)) {
            return true;
        }

        var exponent = Math.max(0, Math.min(attempts - 1, 20));
        var backoff = backoffBase.multipliedBy(1L << exponent);
        if (backoff.compareTo(MAX_BACKOFF) > 0) {
            backoff = MAX_BACKOFF;
        }
        return !now.isBefore(lastAttempt.plus(backoff));
    }
}
