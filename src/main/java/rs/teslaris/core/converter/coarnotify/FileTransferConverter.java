package rs.teslaris.core.converter.coarnotify;

import rs.teslaris.core.dto.coarnotify.FileTransferResponseDTO;
import rs.teslaris.core.model.coarnotify.FileTransfer;

public class FileTransferConverter {

    public static FileTransferResponseDTO toDTO(FileTransfer transfer) {
        return new FileTransferResponseDTO(transfer.getId(), transfer.getDocumentFile().getId(),
            transfer.getNotificationId(), transfer.getStatus(), transfer.getTargetId(),
            transfer.getTargetInbox(), transfer.getAttempts(), transfer.getLastAttemptAt(),
            transfer.getTokenExpiresAt(), transfer.getSha256(), transfer.getRemoteItemUrl(),
            transfer.getRemoteCiteAs(), transfer.getLastError(), transfer.getDownloadedAt(),
            transfer.getDownloadedFromIp());
    }
}
