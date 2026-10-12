package rs.teslaris.core.integration.coarnotify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import rs.teslaris.core.integration.BaseTest;
import rs.teslaris.core.model.coarnotify.FileTransfer;
import rs.teslaris.core.model.coarnotify.FileTransferStatus;
import rs.teslaris.core.model.commontypes.ApproveStatus;
import rs.teslaris.core.model.document.AccessRights;
import rs.teslaris.core.model.document.DocumentFile;
import rs.teslaris.core.model.document.License;
import rs.teslaris.core.model.document.ResourceType;
import rs.teslaris.core.repository.coarnotify.FileTransferRepository;
import rs.teslaris.core.repository.coarnotify.InboxNotificationRepository;
import rs.teslaris.core.repository.document.DatasetRepository;
import rs.teslaris.core.repository.document.DocumentFileRepository;

public class CoarNotifyInboxControllerTest extends BaseTest {

    private static final String LD_JSON = "application/ld+json";

    @Value("${coar-notify.origin.id}")
    private String teslarisId;

    @Value("${coar-notify.dspace.id}")
    private String dspaceId;

    @Autowired
    private FileTransferRepository fileTransferRepository;

    @Autowired
    private InboxNotificationRepository inboxNotificationRepository;

    @Autowired
    private DocumentFileRepository documentFileRepository;

    @Autowired
    private DatasetRepository datasetRepository;


    @Test
    public void shouldAdvertiseLdJson() throws Exception {
        mockMvc.perform(options("/api/coar-notify/inbox"))
            .andExpect(status().isOk())
            .andExpect(header().string("Accept-Post", LD_JSON));
    }

    @Test
    public void shouldCompleteTransferOnAccept() throws Exception {
        var transfer = createSentTransfer();
        var notificationId = "urn:uuid:" + UUID.randomUUID();

        mockMvc.perform(post("/api/coar-notify/inbox").contentType(LD_JSON)
                .content(createNotification(notificationId, "Accept",
                    transfer.getNotificationId(), dspaceId,
                    ",\"context\":{\"id\":\"" + dspaceId + "/items/3b9d\"," +
                        "\"ietf:cite-as\":\"" + dspaceId + "/handle/123456789/5\"}")))
            .andExpect(status().isAccepted());

        var updated = fileTransferRepository.findById(transfer.getId()).orElseThrow();
        assertEquals(FileTransferStatus.ACCEPTED, updated.getStatus());
        assertEquals(dspaceId + "/items/3b9d", updated.getRemoteItemUrl());
        assertEquals(dspaceId + "/handle/123456789/5", updated.getRemoteCiteAs());
        assertTrue(inboxNotificationRepository.existsByNotificationId(notificationId));

        mockMvc.perform(post("/api/coar-notify/inbox").contentType(LD_JSON)
                .content(createNotification(notificationId, "Reject",
                    transfer.getNotificationId(), dspaceId, "")))
            .andExpect(status().isAccepted());

        assertEquals(FileTransferStatus.ACCEPTED,
            fileTransferRepository.findById(transfer.getId()).orElseThrow().getStatus());
    }

    @Test
    public void shouldRecordReasonOnReject() throws Exception {
        var transfer = createSentTransfer();

        mockMvc.perform(post("/api/coar-notify/inbox").contentType(LD_JSON)
                .content(createNotification("urn:uuid:" + UUID.randomUUID(), "Reject",
                    transfer.getNotificationId(), dspaceId,
                    ",\"summary\":\"expired: item URL returned HTTP 410\"")))
            .andExpect(status().isAccepted());

        var updated = fileTransferRepository.findById(transfer.getId()).orElseThrow();
        assertEquals(FileTransferStatus.REJECTED, updated.getStatus());
        assertEquals("expired: item URL returned HTTP 410", updated.getLastError());
    }

    @Test
    public void shouldRefuseUncorrelatedOrForeignNotification() throws Exception {
        var transfer = createSentTransfer();

        mockMvc.perform(post("/api/coar-notify/inbox").contentType(LD_JSON)
                .content(createNotification("urn:uuid:" + UUID.randomUUID(), "Accept",
                    "urn:uuid:" + UUID.randomUUID(), dspaceId, "")))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/coar-notify/inbox").contentType(LD_JSON)
                .content(createNotification("urn:uuid:" + UUID.randomUUID(), "Accept",
                    transfer.getNotificationId(), "https://evil.example.org", "")))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/coar-notify/inbox").contentType(LD_JSON).content("{not json"))
            .andExpect(status().isBadRequest());

        assertEquals(FileTransferStatus.SENT,
            fileTransferRepository.findById(transfer.getId()).orElseThrow().getStatus());
    }

    private String createNotification(String id, String type, String inReplyTo, String origin,
                                      String extra) {
        return "{\"@context\":[\"https://www.w3.org/ns/activitystreams\"," +
            "\"https://coar-notify.net\"],\"id\":\"" + id + "\",\"type\":\"" + type + "\"," +
            "\"inReplyTo\":\"" + inReplyTo + "\"," +
            "\"origin\":{\"id\":\"" + origin + "\",\"type\":\"Service\"}," +
            "\"target\":{\"id\":\"" + teslarisId + "\",\"type\":\"Service\"}," +
            "\"object\":{\"id\":\"" + inReplyTo + "\"}" + extra + "}";
    }

    private FileTransfer createSentTransfer() {
        var documentFile = new DocumentFile();
        documentFile.setFilename("inbox-test.pdf");
        documentFile.setServerFilename(UUID.randomUUID() + ".pdf");
        documentFile.setDescription(new HashSet<>());
        documentFile.setMimeType("application/pdf");
        documentFile.setFileSize(10L);
        documentFile.setResourceType(ResourceType.OFFICIAL_PUBLICATION);
        documentFile.setAccessRights(AccessRights.RESTRICTED_ACCESS);
        documentFile.setLicense(License.BY_NC);
        documentFile.setApproveStatus(ApproveStatus.APPROVED);
        documentFile.setTimestamp(LocalDateTime.now());
        documentFile.setDocument(datasetRepository.findAll().getFirst());

        var transfer = new FileTransfer();
        transfer.setDocumentFile(documentFileRepository.save(documentFile));
        transfer.setNotificationId("urn:uuid:" + UUID.randomUUID());
        transfer.setTargetId(dspaceId);
        transfer.setTargetInbox("http://localhost:8080/server/ldn/inbox");
        transfer.setStatus(FileTransferStatus.SENT);
        transfer.setAttempts(1);
        transfer.setSha256("47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=");
        transfer.setTokenExpiresAt(Instant.now().plusSeconds(3600));
        return fileTransferRepository.save(transfer);
    }
}
