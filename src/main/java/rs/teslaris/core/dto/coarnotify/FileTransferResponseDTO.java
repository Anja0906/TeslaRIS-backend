package rs.teslaris.core.dto.coarnotify;

import java.time.Instant;
import rs.teslaris.core.model.coarnotify.FileTransferStatus;

public record FileTransferResponseDTO(
    Integer id,
    Integer documentFileId,
    String notificationId,
    FileTransferStatus status,
    String targetId,
    String targetInbox,
    int attempts,
    Instant lastAttemptAt,
    Instant tokenExpiresAt,
    String sha256,
    String remoteItemUrl,
    String remoteCiteAs,
    String lastError,
    Instant downloadedAt,
    String downloadedFromIp
) {
}
