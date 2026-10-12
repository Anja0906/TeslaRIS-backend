package rs.teslaris.core.service.interfaces.coarnotify;

import java.util.List;
import org.springframework.stereotype.Service;
import rs.teslaris.core.dto.coarnotify.FileTransferResponseDTO;

@Service
public interface CoarNotifyTransferService {

    FileTransferResponseDTO startTransfer(Integer documentFileId);

    FileTransferResponseDTO retryTransfer(Integer transferId);

    int retryDueTransfers();

    FileTransferResponseDTO getTransfer(Integer transferId);

    List<FileTransferResponseDTO> getTransfersForDocumentFile(Integer documentFileId);

    DownloadAuthorisation authoriseDownload(String serverFilename, String token,
                                            long expiresEpochSeconds, String remoteAddress);

    record DownloadAuthorisation(Result result, String sha256) {

        public enum Result {
            OK,
            INVALID,
            EXPIRED
        }
    }
}
