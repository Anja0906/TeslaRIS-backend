package rs.teslaris.core.service.impl.coarnotify;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import rs.teslaris.core.annotation.Traceable;
import rs.teslaris.core.converter.coarnotify.FileTransferConverter;
import rs.teslaris.core.dto.coarnotify.FileTransferResponseDTO;
import rs.teslaris.core.model.coarnotify.FileTransfer;
import rs.teslaris.core.model.coarnotify.FileTransferStatus;
import rs.teslaris.core.model.commontypes.MultiLingualContent;
import rs.teslaris.core.model.document.DocumentFile;
import rs.teslaris.core.repository.coarnotify.FileTransferRepository;
import rs.teslaris.core.service.interfaces.coarnotify.CoarNotifyTransferService;
import rs.teslaris.core.service.interfaces.coarnotify.FileDigestService;
import rs.teslaris.core.service.interfaces.coarnotify.TransferTokenService;
import rs.teslaris.core.service.interfaces.document.DocumentFileService;
import rs.teslaris.core.util.coarnotify.CoarNotifyPayloadBuilder;
import rs.teslaris.core.util.coarnotify.CoarNotifyPayloadBuilder.IngestOffer;
import rs.teslaris.core.util.coarnotify.TransferRetryPolicy;
import rs.teslaris.core.util.exceptionhandling.exception.NotFoundException;
import rs.teslaris.core.util.session.RestTemplateProvider;
import rs.teslaris.core.util.signposting.FairSignpostingL1Utility;

@Service
@RequiredArgsConstructor
@Slf4j
@Traceable
public class CoarNotifyTransferServiceImpl implements CoarNotifyTransferService {

    private static final List<FileTransferStatus> IN_PROGRESS =
        List.of(FileTransferStatus.PENDING, FileTransferStatus.SENT);

    private static final List<FileTransferStatus> NOT_ACCEPTED =
        List.of(FileTransferStatus.PENDING, FileTransferStatus.SENT, FileTransferStatus.FAILED,
            FileTransferStatus.REJECTED);

    private final FileTransferRepository fileTransferRepository;

    private final DocumentFileService documentFileService;

    private final TransferTokenService transferTokenService;

    private final FileDigestService fileDigestService;

    private final RestTemplateProvider restTemplateProvider;

    private final TransactionTemplate transactionTemplate;

    @Value("${coar-notify.public-base-url}")
    private String publicBaseUrl;

    @Value("${coar-notify.origin.id}")
    private String originId;

    @Value("${coar-notify.origin.inbox}")
    private String originInbox;

    @Value("${coar-notify.origin.name:TeslaRIS}")
    private String originName;

    @Value("${coar-notify.dspace.id}")
    private String targetId;

    @Value("${coar-notify.dspace.inbox}")
    private String targetInbox;

    @Value("${coar-notify.token.ttl-minutes:60}")
    private long tokenTtlMinutes;

    @Value("${coar-notify.max-size-bytes:524288000}")
    private long maxSizeBytes;

    @Value("${coar-notify.retry.max-attempts:5}")
    private int retryMaxAttempts;

    @Value("${coar-notify.retry.backoff-base-seconds:60}")
    private long retryBackoffBaseSeconds;

    @Value("${coar-notify.retry.ack-timeout-minutes:30}")
    private long ackTimeoutMinutes;


    @Override
    public FileTransferResponseDTO startTransfer(Integer documentFileId) {
        var offer = transactionTemplate.execute(status -> {
            var documentFile = documentFileService.findDocumentFileById(documentFileId);
            if (Boolean.TRUE.equals(documentFile.getDeleted()) ||
                Objects.isNull(documentFile.getServerFilename())) {
                throw new NotFoundException("Document file has no stored content.");
            }
            if (Objects.nonNull(documentFile.getFileSize()) &&
                documentFile.getFileSize() > maxSizeBytes) {
                throw new IllegalArgumentException(
                    "File is larger than " + maxSizeBytes + " bytes.");
            }
            if (fileTransferRepository.existsInStatus(documentFileId, targetInbox, IN_PROGRESS)) {
                throw new IllegalArgumentException(
                    "A transfer of this file to " + targetInbox + " is already in progress.");
            }

            var transfer = new FileTransfer();
            transfer.setDocumentFile(documentFile);
            transfer.setTargetId(targetId);
            transfer.setTargetInbox(targetInbox);
            transfer.setSha256(fileDigestService.sha256Base64(documentFile.getServerFilename()));
            return prepareOffer(transfer, documentFile);
        });

        return FileTransferConverter.toDTO(sendOffer(offer));
    }

    @Override
    public FileTransferResponseDTO retryTransfer(Integer transferId) {
        var offer = transactionTemplate.execute(status -> {
            var transfer = findTransfer(transferId);
            if (transfer.getStatus() != FileTransferStatus.FAILED &&
                transfer.getStatus() != FileTransferStatus.REJECTED) {
                throw new IllegalArgumentException(
                    "Only FAILED or REJECTED transfers can be retried.");
            }

            var documentFile = transfer.getDocumentFile();
            transfer.setSha256(fileDigestService.sha256Base64(documentFile.getServerFilename()));
            return prepareOffer(transfer, documentFile);
        });

        return FileTransferConverter.toDTO(sendOffer(offer));
    }

    @Override
    public int retryDueTransfers() {
        var backoffBase = Duration.ofSeconds(retryBackoffBaseSeconds);
        var ackTimeout = Duration.ofMinutes(ackTimeoutMinutes);
        var dueTransferIds = fileTransferRepository.findAllForRetry(NOT_ACCEPTED, retryMaxAttempts)
            .stream()
            .filter(transfer -> TransferRetryPolicy.isDue(transfer, Instant.now(), retryMaxAttempts,
                backoffBase, ackTimeout))
            .map(FileTransfer::getId)
            .toList();

        var resent = 0;
        for (var transferId : dueTransferIds) {
            try {
                var offer = transactionTemplate.execute(status -> {
                    var transfer = findTransfer(transferId);
                    // an answer may have arrived since the list was loaded
                    if (!TransferRetryPolicy.isDue(transfer, Instant.now(), retryMaxAttempts,
                        backoffBase, ackTimeout)) {
                        return null;
                    }

                    log.info("Re-offering transfer {} (status {}, attempt {}).", transfer.getId(),
                        transfer.getStatus(), transfer.getAttempts() + 1);
                    var documentFile = transfer.getDocumentFile();
                    transfer.setSha256(
                        fileDigestService.sha256Base64(documentFile.getServerFilename()));
                    return prepareOffer(transfer, documentFile);
                });
                if (Objects.nonNull(offer)) {
                    sendOffer(offer);
                    resent++;
                }
            } catch (RuntimeException e) {
                log.error("Re-offering transfer {} failed.", transferId, e);
            }
        }
        return resent;
    }

    @Override
    public FileTransferResponseDTO getTransfer(Integer transferId) {
        return FileTransferConverter.toDTO(findTransfer(transferId));
    }

    @Override
    public List<FileTransferResponseDTO> getTransfersForDocumentFile(Integer documentFileId) {
        return fileTransferRepository.findAllForDocumentFile(documentFileId).stream()
            .map(FileTransferConverter::toDTO).toList();
    }

    @Override
    public DownloadAuthorisation authoriseDownload(String serverFilename, String token,
                                                   long expiresEpochSeconds,
                                                   String remoteAddress) {
        var expires = Instant.ofEpochSecond(expiresEpochSeconds);
        var match = fileTransferRepository.findAllForDownload(serverFilename, expires).stream()
            .filter(t -> transferTokenService.matches(token, t.getId(), serverFilename, expires))
            .findFirst();

        if (match.isEmpty()) {
            log.warn("Invalid transfer token for {} from {}.", serverFilename, remoteAddress);
            return new DownloadAuthorisation(DownloadAuthorisation.Result.INVALID, null);
        }

        var transfer = match.get();
        if (!Instant.now().isBefore(expires) || !IN_PROGRESS.contains(transfer.getStatus())) {
            log.warn("Expired transfer token for {} (transfer {}, status {}).", serverFilename,
                transfer.getId(), transfer.getStatus());
            return new DownloadAuthorisation(DownloadAuthorisation.Result.EXPIRED, null);
        }

        transfer.setDownloadedAt(Instant.now());
        transfer.setDownloadedFromIp(remoteAddress);
        fileTransferRepository.save(transfer);

        log.info("File {} downloaded for transfer {} by {}.", serverFilename, transfer.getId(),
            remoteAddress);
        return new DownloadAuthorisation(DownloadAuthorisation.Result.OK, transfer.getSha256());
    }

    private PreparedOffer prepareOffer(FileTransfer transfer, DocumentFile documentFile) {
        var expires = Instant.now().plus(tokenTtlMinutes, ChronoUnit.MINUTES)
            .truncatedTo(ChronoUnit.SECONDS);

        // DSpace rejects a notification id it has already received
        transfer.setNotificationId("urn:uuid:" + UUID.randomUUID());
        transfer.setTokenExpiresAt(expires);
        transfer.setStatus(FileTransferStatus.PENDING);
        transfer.setAttempts(transfer.getAttempts() + 1);
        transfer.setLastAttemptAt(Instant.now());
        transfer.setLastError(null);
        transfer.setDownloadedAt(null);
        transfer.setDownloadedFromIp(null);
        var savedTransfer = fileTransferRepository.save(transfer);

        var serverFilename = documentFile.getServerFilename();
        var token = transferTokenService.mint(savedTransfer.getId(), serverFilename, expires);
        var itemUrl = publicBaseUrl + "/api/file/" + serverFilename + "?transferToken=" + token +
            "&expires=" + expires.getEpochSecond();

        var payload = CoarNotifyPayloadBuilder.buildIngestOffer(new IngestOffer(
            savedTransfer.getNotificationId(), originId, originName, originInbox,
            savedTransfer.getTargetId(), savedTransfer.getTargetInbox(),
            getLandingPage(documentFile), getCiteAs(documentFile), getTitle(documentFile),
            itemUrl, documentFile.getMimeType()));

        return new PreparedOffer(savedTransfer, payload, documentFile.getId());
    }

    private FileTransfer sendOffer(PreparedOffer offer) {
        var transfer = offer.transfer();
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/ld+json"));

        try {
            var request = new HttpEntity<>(offer.payload(), headers);
            var response = restTemplateProvider.provideRestTemplate()
                .postForEntity(transfer.getTargetInbox(), request, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                transfer.setStatus(FileTransferStatus.SENT);
            } else {
                markFailed(transfer, "Inbox answered HTTP " + response.getStatusCode().value());
            }
        } catch (RestClientResponseException e) {
            markFailed(transfer, "Inbox answered HTTP " + e.getStatusCode().value() + ": " +
                e.getResponseBodyAsString());
        } catch (RestClientException e) {
            markFailed(transfer, "Inbox unreachable: " + e.getMessage());
        }

        log.info("COAR Notify offer {} for document file {} to {}: {}.",
            transfer.getNotificationId(), offer.documentFileId(), transfer.getTargetInbox(),
            transfer.getStatus());
        return transactionTemplate.execute(status -> fileTransferRepository.save(transfer));
    }

    private void markFailed(FileTransfer transfer, String reason) {
        transfer.setStatus(FileTransferStatus.FAILED);
        transfer.setLastError(StringUtils.abbreviate(reason, 2000));
    }

    private FileTransfer findTransfer(Integer transferId) {
        return fileTransferRepository.findById(transferId)
            .orElseThrow(
                () -> new NotFoundException("File transfer with given id does not exist."));
    }

    private String getLandingPage(DocumentFile documentFile) {
        var landingPage = FairSignpostingL1Utility.getLandingPageUrl(documentFile);
        return Objects.nonNull(landingPage) ? landingPage :
            publicBaseUrl + "/api/document-file/" + documentFile.getId();
    }

    private String getCiteAs(DocumentFile documentFile) {
        var document = documentFile.getDocument();
        if (Objects.isNull(document) || StringUtils.isBlank(document.getDoi())) {
            return null;
        }

        return "https://doi.org/" + document.getDoi();
    }

    private String getTitle(DocumentFile documentFile) {
        var document = documentFile.getDocument();
        if (Objects.isNull(document) || Objects.isNull(document.getTitle())) {
            return documentFile.getFilename();
        }

        return document.getTitle().stream()
            .sorted(Comparator.comparing((MultiLingualContent title) -> !isEnglish(title))
                .thenComparingInt(MultiLingualContent::getPriority))
            .map(MultiLingualContent::getContent)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(documentFile.getFilename());
    }

    private boolean isEnglish(MultiLingualContent content) {
        return Objects.nonNull(content.getLanguage()) &&
            "EN".equalsIgnoreCase(content.getLanguage().getLanguageTag());
    }

    private record PreparedOffer(FileTransfer transfer, String payload, Integer documentFileId) {
    }
}
