package rs.teslaris.core.controller.coarnotify;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rs.teslaris.core.annotation.Traceable;
import rs.teslaris.core.dto.coarnotify.FileTransferResponseDTO;
import rs.teslaris.core.service.interfaces.coarnotify.CoarNotifyTransferService;

@RestController
@RequestMapping("/api/coar-notify/transfer")
@RequiredArgsConstructor
@Traceable
public class CoarNotifyTransferController {

    private final CoarNotifyTransferService coarNotifyTransferService;

    @GetMapping
    @PreAuthorize("hasAuthority('TRANSFER_DOCUMENT_FILES')")
    public List<FileTransferResponseDTO> getTransfersForDocumentFile(
        @RequestParam Integer documentFileId) {
        return coarNotifyTransferService.getTransfersForDocumentFile(documentFileId);
    }

    @GetMapping("/{transferId}")
    @PreAuthorize("hasAuthority('TRANSFER_DOCUMENT_FILES')")
    public FileTransferResponseDTO getTransfer(@PathVariable Integer transferId) {
        return coarNotifyTransferService.getTransfer(transferId);
    }

    @PostMapping("/{documentFileId}")
    @PreAuthorize("hasAuthority('TRANSFER_DOCUMENT_FILES')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public FileTransferResponseDTO startTransfer(@PathVariable Integer documentFileId) {
        return coarNotifyTransferService.startTransfer(documentFileId);
    }

    @PostMapping("/{transferId}/retry")
    @PreAuthorize("hasAuthority('TRANSFER_DOCUMENT_FILES')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public FileTransferResponseDTO retryTransfer(@PathVariable Integer transferId) {
        return coarNotifyTransferService.retryTransfer(transferId);
    }
}
