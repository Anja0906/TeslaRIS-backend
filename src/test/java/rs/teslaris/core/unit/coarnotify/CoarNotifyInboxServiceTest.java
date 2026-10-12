package rs.teslaris.core.unit.coarnotify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import rs.teslaris.core.model.coarnotify.FileTransfer;
import rs.teslaris.core.model.coarnotify.FileTransferStatus;
import rs.teslaris.core.model.coarnotify.InboxNotification;
import rs.teslaris.core.repository.coarnotify.FileTransferRepository;
import rs.teslaris.core.repository.coarnotify.InboxNotificationRepository;
import rs.teslaris.core.service.impl.coarnotify.CoarNotifyInboxServiceImpl;

@ExtendWith(MockitoExtension.class)
public class CoarNotifyInboxServiceTest {

    private static final String TESLARIS_ID = "http://host.docker.internal:8081";

    private static final String DSPACE_ID = "http://localhost:4000";

    private static final String OFFER_ID = "urn:uuid:5c1d2a8e-0000-4000-8000-000000000001";

    @Mock
    private InboxNotificationRepository inboxNotificationRepository;

    @Mock
    private FileTransferRepository fileTransferRepository;

    @InjectMocks
    private CoarNotifyInboxServiceImpl coarNotifyInboxService;

    private FileTransfer transfer;


    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(coarNotifyInboxService, "originId", TESLARIS_ID);

        transfer = new FileTransfer();
        transfer.setId(9);
        transfer.setNotificationId(OFFER_ID);
        transfer.setTargetId(DSPACE_ID);
        transfer.setTargetInbox("http://localhost:8080/server/ldn/inbox");
        transfer.setStatus(FileTransferStatus.SENT);
        transfer.setSha256("x");
        transfer.setTokenExpiresAt(Instant.now().plusSeconds(600));

        lenient().when(fileTransferRepository.findByNotificationId(OFFER_ID))
            .thenReturn(Optional.of(transfer));
    }

    @Test
    public void shouldMarkTransferAcceptedWithCreatedItem() {
        // When
        var result = coarNotifyInboxService.receiveNotification(createAccept("urn:uuid:ack-1"),
            "172.23.0.1");

        // Then
        assertEquals(HttpStatus.ACCEPTED, result.status());
        assertEquals(FileTransferStatus.ACCEPTED, transfer.getStatus());
        assertEquals(DSPACE_ID + "/items/3b9d", transfer.getRemoteItemUrl());
        assertEquals(DSPACE_ID + "/handle/123456789/5", transfer.getRemoteCiteAs());

        var stored = getSavedNotification();
        assertTrue(stored.isProcessed());
        assertEquals("Accept", stored.getType());
        assertEquals(OFFER_ID, stored.getInReplyTo());
        assertEquals("172.23.0.1", stored.getRemoteAddress());
    }

    @Test
    public void shouldMarkTransferRejectedWithSummary() {
        // Given
        var body = createNotification("urn:uuid:ack-2", "Reject", OFFER_ID, DSPACE_ID, TESLARIS_ID,
            ",\"summary\":\"digest-mismatch: expected sha-256 AAAA\"");

        // When
        var result = coarNotifyInboxService.receiveNotification(body, "172.23.0.1");

        // Then
        assertEquals(HttpStatus.ACCEPTED, result.status());
        assertEquals(FileTransferStatus.REJECTED, transfer.getStatus());
        assertEquals("digest-mismatch: expected sha-256 AAAA", transfer.getLastError());
        assertNull(transfer.getRemoteItemUrl());
    }

    @Test
    public void shouldRefuseUnknownInReplyTo() {
        // Given
        var body = createNotification("urn:uuid:ack-3", "Accept", "urn:uuid:never-sent", DSPACE_ID,
            TESLARIS_ID, "");

        // When
        var result = coarNotifyInboxService.receiveNotification(body, "1.2.3.4");

        // Then
        assertEquals(HttpStatus.FORBIDDEN, result.status());
        verify(inboxNotificationRepository, never()).save(any());
    }

    @Test
    public void shouldRefuseNotificationFromOtherRepository() {
        // Given
        var body = createNotification("urn:uuid:ack-4", "Accept", OFFER_ID,
            "https://evil.example.org", TESLARIS_ID, "");

        // When
        var result = coarNotifyInboxService.receiveNotification(body, "1.2.3.4");

        // Then
        assertEquals(HttpStatus.FORBIDDEN, result.status());
        assertEquals(FileTransferStatus.SENT, transfer.getStatus());
    }

    @Test
    public void shouldRefuseNotificationForOtherSystem() {
        // Given
        var body = createNotification("urn:uuid:ack-5", "Accept", OFFER_ID, DSPACE_ID,
            "https://someone-else.org", "");

        // When
        var result = coarNotifyInboxService.receiveNotification(body, "1.2.3.4");

        // Then
        assertEquals(HttpStatus.FORBIDDEN, result.status());
        assertEquals(FileTransferStatus.SENT, transfer.getStatus());
    }

    @Test
    public void shouldIgnoreDuplicateNotification() {
        // Given
        when(inboxNotificationRepository.existsByNotificationId("urn:uuid:ack-6"))
            .thenReturn(true);

        // When
        var result =
            coarNotifyInboxService.receiveNotification(createAccept("urn:uuid:ack-6"), "x");

        // Then
        assertEquals(HttpStatus.ACCEPTED, result.status());
        assertEquals(FileTransferStatus.SENT, transfer.getStatus());
        verify(inboxNotificationRepository, never()).save(any());
    }

    @Test
    public void shouldNotChangeAlreadyAnsweredTransfer() {
        // Given
        transfer.setStatus(FileTransferStatus.REJECTED);

        // When
        var result =
            coarNotifyInboxService.receiveNotification(createAccept("urn:uuid:ack-7"), "x");

        // Then
        assertEquals(HttpStatus.ACCEPTED, result.status());
        assertEquals(FileTransferStatus.REJECTED, transfer.getStatus());
        assertFalse(getSavedNotification().isProcessed());
    }

    @Test
    public void shouldRefuseMalformedOrIncompleteNotification() {
        // Given
        var withoutInReplyTo = createAccept("urn:uuid:ack-8")
            .replace("\"inReplyTo\":\"" + OFFER_ID + "\",", "");
        var relativeId =
            createNotification("relative-id", "Accept", OFFER_ID, DSPACE_ID, TESLARIS_ID, "");

        // When & Then
        assertEquals(HttpStatus.BAD_REQUEST,
            coarNotifyInboxService.receiveNotification("not json", "x").status());
        assertEquals(HttpStatus.BAD_REQUEST,
            coarNotifyInboxService.receiveNotification("[1,2]", "x").status());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, coarNotifyInboxService.receiveNotification(
            "{\"id\":\"urn:uuid:a\",\"type\":\"Accept\"}", "x").status());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
            coarNotifyInboxService.receiveNotification(relativeId, "x").status());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
            coarNotifyInboxService.receiveNotification(withoutInReplyTo, "x").status());
        verify(inboxNotificationRepository, never()).save(any());
    }

    @Test
    public void shouldStoreOtherTypesUnprocessed() {
        // Given
        var body = createNotification("urn:uuid:ack-9", "Announce", OFFER_ID, DSPACE_ID,
            TESLARIS_ID, "");

        // When
        var result = coarNotifyInboxService.receiveNotification(body, "x");

        // Then
        assertEquals(HttpStatus.ACCEPTED, result.status());
        assertFalse(getSavedNotification().isProcessed());
        assertEquals(FileTransferStatus.SENT, transfer.getStatus());
        verify(fileTransferRepository, never()).findByNotificationId(anyString());
    }

    private String createAccept(String id) {
        return createNotification(id, "Accept", OFFER_ID, DSPACE_ID, TESLARIS_ID,
            ",\"context\":{\"id\":\"" + DSPACE_ID + "/items/3b9d\"," +
                "\"ietf:cite-as\":\"" + DSPACE_ID + "/handle/123456789/5\",\"type\":[\"Page\"]}");
    }

    private String createNotification(String id, String type, String inReplyTo, String origin,
                                      String target, String extra) {
        return "{\"@context\":[\"https://www.w3.org/ns/activitystreams\",\"https://coar-notify.net\"]," +
            "\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"inReplyTo\":\"" + inReplyTo + "\"," +
            "\"actor\":{\"id\":\"" + origin + "\",\"type\":\"Service\"}," +
            "\"origin\":{\"id\":\"" + origin + "\",\"inbox\":\"" + origin +
            "/server/ldn/inbox\",\"type\":\"Service\"}," +
            "\"target\":{\"id\":\"" + target + "\",\"inbox\":\"" + target +
            "/api/coar-notify/inbox\",\"type\":\"Service\"}," +
            "\"object\":{\"id\":\"" + inReplyTo + "\"," +
            "\"type\":[\"Offer\",\"coar-notify:IngestAction\"]}" + extra + "}";
    }

    private InboxNotification getSavedNotification() {
        var captor = ArgumentCaptor.forClass(InboxNotification.class);
        verify(inboxNotificationRepository).save(captor.capture());
        return captor.getValue();
    }
}
