package rs.teslaris.core.unit.coarnotify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import rs.teslaris.core.model.coarnotify.FileTransfer;
import rs.teslaris.core.model.coarnotify.FileTransferStatus;
import rs.teslaris.core.model.commontypes.LanguageTag;
import rs.teslaris.core.model.commontypes.MultiLingualContent;
import rs.teslaris.core.model.document.DocumentFile;
import rs.teslaris.core.model.document.JournalPublication;
import rs.teslaris.core.repository.coarnotify.FileTransferRepository;
import rs.teslaris.core.service.impl.coarnotify.CoarNotifyTransferServiceImpl;
import rs.teslaris.core.service.impl.coarnotify.TransferTokenServiceImpl;
import rs.teslaris.core.service.interfaces.coarnotify.CoarNotifyTransferService.DownloadAuthorisation;
import rs.teslaris.core.service.interfaces.coarnotify.FileDigestService;
import rs.teslaris.core.service.interfaces.document.DocumentFileService;
import rs.teslaris.core.util.session.RestTemplateProvider;
import rs.teslaris.core.util.signposting.FairSignpostingL1Utility;

@ExtendWith(MockitoExtension.class)
public class CoarNotifyTransferServiceTest {

    private static final String BASE_URL = "http://host.docker.internal:8081";

    private static final String ORIGIN_INBOX = BASE_URL + "/api/coar-notify/inbox";

    private static final String DSPACE_INBOX = "http://localhost:8080/server/ldn/inbox";

    private static final String DIGEST = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";

    private final TransferTokenServiceImpl transferTokenService =
        new TransferTokenServiceImpl("0123456789abcdef0123456789abcdef");

    private final List<FileTransfer> savedTransfers = new ArrayList<>();

    @Mock
    private FileTransferRepository fileTransferRepository;

    @Mock
    private DocumentFileService documentFileService;

    @Mock
    private FileDigestService fileDigestService;

    @Mock
    private RestTemplateProvider restTemplateProvider;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private CoarNotifyTransferServiceImpl coarNotifyTransferService;

    private DocumentFile documentFile;


    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(coarNotifyTransferService, "transferTokenService",
            transferTokenService);
        ReflectionTestUtils.setField(coarNotifyTransferService, "publicBaseUrl", BASE_URL);
        ReflectionTestUtils.setField(coarNotifyTransferService, "originId", BASE_URL);
        ReflectionTestUtils.setField(coarNotifyTransferService, "originInbox", ORIGIN_INBOX);
        ReflectionTestUtils.setField(coarNotifyTransferService, "originName", "TeslaRIS");
        ReflectionTestUtils.setField(coarNotifyTransferService, "targetId", "http://localhost:4000");
        ReflectionTestUtils.setField(coarNotifyTransferService, "targetInbox", DSPACE_INBOX);
        ReflectionTestUtils.setField(coarNotifyTransferService, "tokenTtlMinutes", 60L);
        ReflectionTestUtils.setField(coarNotifyTransferService, "retryMaxAttempts", 5);
        ReflectionTestUtils.setField(coarNotifyTransferService, "retryBackoffBaseSeconds", 60L);
        ReflectionTestUtils.setField(coarNotifyTransferService, "ackTimeoutMinutes", 30L);
        ReflectionTestUtils.setField(coarNotifyTransferService, "maxSizeBytes", 1000L);
        ReflectionTestUtils.setField(FairSignpostingL1Utility.class, "frontendUrl",
            "http://localhost:5173/");
        ReflectionTestUtils.setField(FairSignpostingL1Utility.class, "defaultLocale", "sr");

        var document = new JournalPublication();
        document.setId(42);
        document.setDoi("10.5555/12345680");
        document.setTitle(Set.of(
            new MultiLingualContent(new LanguageTag("SR", "Srpski"), "Naslov", 1),
            new MultiLingualContent(new LanguageTag("EN", "English"), "The title", 2)));

        documentFile = new DocumentFile();
        documentFile.setId(5);
        documentFile.setFilename("paper.pdf");
        documentFile.setServerFilename("0f3c.pdf");
        documentFile.setMimeType("application/pdf");
        documentFile.setFileSize(500L);
        documentFile.setDocument(document);

        lenient().when(documentFileService.findDocumentFileById(5)).thenReturn(documentFile);
        lenient().when(fileDigestService.sha256Base64("0f3c.pdf")).thenReturn(DIGEST);
        lenient().when(restTemplateProvider.provideRestTemplate()).thenReturn(restTemplate);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
            ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        lenient().when(fileTransferRepository.save(any(FileTransfer.class)))
            .thenAnswer(invocation -> {
                FileTransfer transfer = invocation.getArgument(0);
                if (Objects.isNull(transfer.getId())) {
                    transfer.setId(100 + savedTransfers.size());
                }
                savedTransfers.add(transfer);
                return transfer;
            });
    }

    @Test
    public void shouldSendIngestOfferWithTokenisedItemUrl() throws Exception {
        // Given
        var request = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.postForEntity(eq(DSPACE_INBOX), request.capture(), eq(String.class)))
            .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).build());

        // When
        var result = coarNotifyTransferService.startTransfer(5);

        // Then
        assertEquals(FileTransferStatus.SENT, result.status());
        assertEquals(1, result.attempts());
        assertEquals(DIGEST, result.sha256());
        assertTrue(result.notificationId().startsWith("urn:uuid:"));

        HttpEntity<?> entity = request.getValue();
        assertEquals("application/ld+json", entity.getHeaders().getContentType().toString());

        var offer = new ObjectMapper().readTree((String) entity.getBody());
        assertEquals("https://www.w3.org/ns/activitystreams",
            offer.path("@context").path(0).asText());
        assertEquals("https://coar-notify.net", offer.path("@context").path(1).asText());
        assertEquals(result.notificationId(), offer.path("id").asText());
        assertEquals("Offer", offer.path("type").path(0).asText());
        assertEquals("coar-notify:IngestAction", offer.path("type").path(1).asText());
        assertEquals(ORIGIN_INBOX, offer.path("origin").path("inbox").asText());
        assertEquals(DSPACE_INBOX, offer.path("target").path("inbox").asText());

        var object = offer.path("object");
        assertEquals("http://localhost:5173/sr/scientific-results/journal-publication/42",
            object.path("id").asText());
        assertEquals("https://doi.org/10.5555/12345680", object.path("ietf:cite-as").asText());
        assertEquals("The title", object.path("sorg:name").asText());

        var item = object.path("ietf:item");
        assertEquals("application/pdf", item.path("mediaType").asText());
        var itemUrl = item.path("id").asText();
        assertTrue(itemUrl.startsWith(BASE_URL + "/api/file/0f3c.pdf?transferToken="));
        var expires = Instant.ofEpochSecond(Long.parseLong(itemUrl.replaceAll(".*expires=", "")));
        var token = itemUrl.replaceAll(".*transferToken=([0-9a-f]+)&.*", "$1");
        assertEquals(result.tokenExpiresAt(), expires);
        assertTrue(transferTokenService.matches(token, result.id(), "0f3c.pdf", expires));
    }

    @Test
    public void shouldMarkTransferFailedWhenInboxFailsAndRetryWithNewNotificationId() {
        // Given
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Error",
                new HttpHeaders(), "Duplicate LDN Message ID".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

        // When
        var result = coarNotifyTransferService.startTransfer(5);

        // Then
        assertEquals(FileTransferStatus.FAILED, result.status());
        assertTrue(result.lastError().contains("500"));
        assertTrue(result.lastError().contains("Duplicate LDN Message ID"));

        // Given
        when(fileTransferRepository.findById(result.id())).thenReturn(
            Optional.of(savedTransfers.getLast()));
        doThrow(new ResourceAccessException("Connection refused"))
            .when(restTemplate).postForEntity(anyString(), any(), eq(String.class));

        // When
        var retried = coarNotifyTransferService.retryTransfer(result.id());

        // Then
        assertEquals(FileTransferStatus.FAILED, retried.status());
        assertEquals(2, retried.attempts());
        assertTrue(retried.lastError().contains("Connection refused"));
        assertNotEquals(result.notificationId(), retried.notificationId());
    }

    @Test
    public void shouldNotStartTransferWhileOneIsInProgress() {
        // Given
        when(fileTransferRepository.existsInStatus(eq(5), eq(DSPACE_INBOX), any()))
            .thenReturn(true);

        // When
        assertThrows(IllegalArgumentException.class,
            () -> coarNotifyTransferService.startTransfer(5));

        // Then
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    public void shouldNotStartTransferForFileAboveSizeLimit() {
        // Given
        documentFile.setFileSize(5000L);

        // When
        assertThrows(IllegalArgumentException.class,
            () -> coarNotifyTransferService.startTransfer(5));

        // Then
        verify(fileDigestService, never()).sha256Base64(anyString());
    }

    @Test
    public void shouldOnlyRetryFailedOrRejectedTransfer() {
        // Given
        var transfer = createTransfer(FileTransferStatus.SENT, Instant.now().plusSeconds(3600));
        when(fileTransferRepository.findById(transfer.getId())).thenReturn(Optional.of(transfer));

        // When & Then
        assertThrows(IllegalArgumentException.class,
            () -> coarNotifyTransferService.retryTransfer(transfer.getId()));
    }

    @Test
    public void shouldAuthoriseDownloadWithValidToken() {
        // Given
        var expires = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS);
        var transfer = createTransfer(FileTransferStatus.SENT, expires);
        when(fileTransferRepository.findAllForDownload("0f3c.pdf", expires))
            .thenReturn(List.of(transfer));
        var token = transferTokenService.mint(transfer.getId(), "0f3c.pdf", expires);

        // When
        var result = coarNotifyTransferService.authoriseDownload("0f3c.pdf", token,
            expires.getEpochSecond(), "172.23.0.2");

        // Then
        assertEquals(DownloadAuthorisation.Result.OK, result.result());
        assertEquals(DIGEST, result.sha256());
        assertEquals("172.23.0.2", transfer.getDownloadedFromIp());
        assertNotNull(transfer.getDownloadedAt());
    }

    @Test
    public void shouldRejectDownloadWithTokenOfOtherTransfer() {
        // Given
        var expires = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS);
        var transfer = createTransfer(FileTransferStatus.SENT, expires);
        when(fileTransferRepository.findAllForDownload("0f3c.pdf", expires))
            .thenReturn(List.of(transfer));
        var token = transferTokenService.mint(transfer.getId() + 1, "0f3c.pdf", expires);

        // When
        var result = coarNotifyTransferService.authoriseDownload("0f3c.pdf", token,
            expires.getEpochSecond(), "1.2.3.4");

        // Then
        assertEquals(DownloadAuthorisation.Result.INVALID, result.result());
        assertNull(transfer.getDownloadedAt());
    }

    @Test
    public void shouldRejectDownloadWithExpiredToken() {
        // Given
        var expires = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
        var transfer = createTransfer(FileTransferStatus.SENT, expires);
        when(fileTransferRepository.findAllForDownload("0f3c.pdf", expires))
            .thenReturn(List.of(transfer));
        var token = transferTokenService.mint(transfer.getId(), "0f3c.pdf", expires);

        // When
        var result = coarNotifyTransferService.authoriseDownload("0f3c.pdf", token,
            expires.getEpochSecond(), "1.2.3.4");

        // Then
        assertEquals(DownloadAuthorisation.Result.EXPIRED, result.result());
    }

    @Test
    public void shouldRejectDownloadForAnsweredTransfer() {
        // Given
        var expires = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS);
        var transfer = createTransfer(FileTransferStatus.ACCEPTED, expires);
        when(fileTransferRepository.findAllForDownload("0f3c.pdf", expires))
            .thenReturn(List.of(transfer));
        var token = transferTokenService.mint(transfer.getId(), "0f3c.pdf", expires);

        // When
        var result = coarNotifyTransferService.authoriseDownload("0f3c.pdf", token,
            expires.getEpochSecond(), "1.2.3.4");

        // Then
        assertEquals(DownloadAuthorisation.Result.EXPIRED, result.result());
    }

    @Test
    public void shouldReOfferOnlyDueTransfers() {
        // Given
        var hourAgo = Instant.now().minusSeconds(3600);
        var failed = createTransfer(FileTransferStatus.FAILED, Instant.now());
        failed.setId(1);
        failed.setLastAttemptAt(hourAgo);
        var fixableReject = createTransfer(FileTransferStatus.REJECTED, Instant.now());
        fixableReject.setId(2);
        fixableReject.setLastError("expired: item URL returned HTTP 410");
        fixableReject.setLastAttemptAt(hourAgo);
        var finalReject = createTransfer(FileTransferStatus.REJECTED, Instant.now());
        finalReject.setId(3);
        finalReject.setLastError("too-large: 900000000 bytes");
        finalReject.setLastAttemptAt(hourAgo);
        var recentlySent = createTransfer(FileTransferStatus.SENT, Instant.now());
        recentlySent.setId(4);
        recentlySent.setLastAttemptAt(Instant.now().minusSeconds(60));

        var transfers = List.of(failed, fixableReject, finalReject, recentlySent);
        when(fileTransferRepository.findAllForRetry(any(), eq(5))).thenReturn(transfers);
        transfers.forEach(transfer ->
            lenient().when(fileTransferRepository.findById(transfer.getId()))
                .thenReturn(Optional.of(transfer)));
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).build());

        // When
        var resent = coarNotifyTransferService.retryDueTransfers();

        // Then
        assertEquals(2, resent);
        assertEquals(FileTransferStatus.SENT, failed.getStatus());
        assertEquals(2, failed.getAttempts());
        assertNotEquals("urn:uuid:test", failed.getNotificationId());
        assertEquals(FileTransferStatus.SENT, fixableReject.getStatus());
        assertNull(fixableReject.getLastError());
        assertEquals(FileTransferStatus.REJECTED, finalReject.getStatus());
        assertEquals(1, recentlySent.getAttempts());
    }

    private FileTransfer createTransfer(FileTransferStatus status, Instant expires) {
        var transfer = new FileTransfer();
        transfer.setId(77);
        transfer.setDocumentFile(documentFile);
        transfer.setNotificationId("urn:uuid:test");
        transfer.setTargetId("http://localhost:4000");
        transfer.setTargetInbox(DSPACE_INBOX);
        transfer.setStatus(status);
        transfer.setAttempts(1);
        transfer.setSha256(DIGEST);
        transfer.setTokenExpiresAt(expires);
        return transfer;
    }
}
