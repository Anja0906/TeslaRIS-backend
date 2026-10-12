package rs.teslaris.core.integration.coarnotify;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import rs.teslaris.core.integration.BaseTest;
import rs.teslaris.core.model.commontypes.ApproveStatus;
import rs.teslaris.core.model.document.AccessRights;
import rs.teslaris.core.model.document.DocumentFile;
import rs.teslaris.core.model.document.License;
import rs.teslaris.core.model.document.ResourceType;
import rs.teslaris.core.repository.coarnotify.FileTransferRepository;
import rs.teslaris.core.repository.document.DatasetRepository;
import rs.teslaris.core.repository.document.DocumentFileRepository;
import rs.teslaris.core.service.interfaces.document.FileService;

public class CoarNotifyTransferControllerTest extends BaseTest {

    private static final byte[] CONTENT =
        "%PDF-1.4\nCOAR Notify transfer test\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    private static final List<JsonNode> OFFERS = new CopyOnWriteArrayList<>();

    private static final HttpServer DSPACE_INBOX = startInbox();

    private static volatile int inboxStatus = 202;

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private FileService fileService;

    @Autowired
    private DocumentFileRepository documentFileRepository;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private FileTransferRepository fileTransferRepository;


    @DynamicPropertySource
    static void coarNotifyProperties(DynamicPropertyRegistry registry) {
        registry.add("coar-notify.dspace.inbox",
            () -> "http://127.0.0.1:" + DSPACE_INBOX.getAddress().getPort() + "/server/ldn/inbox");
        registry.add("coar-notify.token.secret", () -> "integration-test-secret-0123456789ab");
        registry.add("coar-notify.retry.enabled", () -> "false");
    }

    private static HttpServer startInbox() {
        try {
            var server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/server/ldn/inbox", exchange -> {
                OFFERS.add(new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes()));
                exchange.sendResponseHeaders(inboxStatus, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopInbox() {
        DSPACE_INBOX.stop(0);
    }

    @BeforeEach
    void resetInbox() {
        OFFERS.clear();
        inboxStatus = 202;
    }

    @Test
    public void shouldOfferRestrictedFileAndServeItWithToken() throws Exception {
        var documentFile = storeRestrictedFile();
        var admin = login("admin@admin.com", "admin");

        var started = mockMvc.perform(post("/api/coar-notify/transfer/{id}", documentFile.getId())
                .with(admin))
            .andExpect(status().isAccepted()).andReturn();
        var transfer = mapper.readTree(started.getResponse().getContentAsString());
        assertEquals("SENT", transfer.path("status").asText());

        assertEquals(1, OFFERS.size());
        var offer = OFFERS.get(0);
        assertEquals(transfer.path("notificationId").asText(), offer.path("id").asText());
        assertEquals("coar-notify:IngestAction", offer.path("type").path(1).asText());
        var itemUrl = URI.create(offer.path("object").path("ietf:item").path("id").asText());
        assertEquals("/api/file/" + documentFile.getServerFilename(), itemUrl.getPath());

        var anonymous = mockMvc.perform(get(itemUrl.getPath())).andReturn();
        assertNotEquals(200, anonymous.getResponse().getStatus());

        var tampered = itemUrl.getRawQuery()
            .replaceFirst("transferToken=[0-9a-f]+", "transferToken=" + "0".repeat(64));
        mockMvc.perform(get(itemUrl.getPath() + "?" + tampered))
            .andExpect(status().isUnauthorized());

        MvcResult download = mockMvc.perform(get(itemUrl.getPath() + "?" + itemUrl.getRawQuery()))
            .andReturn();
        download.getAsyncResult();
        var response = mockMvc.perform(asyncDispatch(download)).andExpect(status().isOk())
            .andReturn().getResponse();
        assertArrayEquals(CONTENT, response.getContentAsByteArray());
        var expectedDigest = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(CONTENT));
        assertEquals("sha-256=:" + expectedDigest + ":", response.getHeader("Repr-Digest"));
        assertTrue(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)
            .endsWith("filename*=UTF-8''coar-transfer-test.pdf"));

        var audited = fileTransferRepository.findById(transfer.path("id").asInt()).orElseThrow();
        assertNotNull(audited.getDownloadedAt());
        assertNotNull(audited.getDownloadedFromIp());

        mockMvc.perform(get("/api/coar-notify/transfer/{id}", transfer.path("id").asInt())
                .with(admin))
            .andExpect(status().isOk());
        var list = mockMvc.perform(get("/api/coar-notify/transfer")
                .param("documentFileId", String.valueOf(documentFile.getId()))
                .with(admin))
            .andExpect(status().isOk()).andReturn();
        assertEquals(1, mapper.readTree(list.getResponse().getContentAsString()).size());

        mockMvc.perform(post("/api/coar-notify/transfer/{id}", documentFile.getId())
                .with(admin))
            .andExpect(status().isBadRequest());
    }

    @Test
    public void shouldRetryFailedTransferWithNewNotificationId() throws Exception {
        var documentFile = storeRestrictedFile();
        var admin = login("admin@admin.com", "admin");
        inboxStatus = 500;

        var failed = mapper.readTree(mockMvc.perform(
                post("/api/coar-notify/transfer/{id}", documentFile.getId())
                    .with(admin))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertEquals("FAILED", failed.path("status").asText());
        assertTrue(failed.path("lastError").asText().contains("500"));

        inboxStatus = 202;
        var retried = mapper.readTree(mockMvc.perform(
                post("/api/coar-notify/transfer/{id}/retry", failed.path("id").asInt())
                    .with(admin))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertEquals("SENT", retried.path("status").asText());
        assertEquals(2, retried.path("attempts").asInt());
        assertNotEquals(failed.path("notificationId").asText(),
            retried.path("notificationId").asText());
        assertEquals(2, OFFERS.size());
    }

    @Test
    public void shouldNotStartTransferWithoutPrivilege() throws Exception {
        var documentFile = storeRestrictedFile();
        var editor = login("editor@editor.com", "editor");

        mockMvc.perform(post("/api/coar-notify/transfer/{id}", documentFile.getId())
                .with(editor))
            .andExpect(status().isUnauthorized());
        assertTrue(OFFERS.isEmpty());
    }

    private RequestPostProcessor login(String email, String password) throws Exception {
        var result = mockMvc.perform(post("/api/user/authenticate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}"))
            .andExpect(status().isOk()).andReturn();
        var token =
            mapper.readTree(result.getResponse().getContentAsString()).path("token").asText();
        var fingerprint = result.getResponse().getCookie("jwt-security-fingerprint");
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            request.setCookies(fingerprint);
            return request;
        };
    }

    private DocumentFile storeRestrictedFile() {
        var document = datasetRepository.findAll().get(0);
        var serverFilename = fileService.store(new ByteArrayResource(CONTENT),
            UUID.randomUUID().toString(), "coar-transfer-test.pdf");

        var documentFile = new DocumentFile();
        documentFile.setFilename("coar-transfer-test.pdf");
        documentFile.setServerFilename(serverFilename);
        documentFile.setDescription(new HashSet<>());
        documentFile.setMimeType("application/pdf");
        documentFile.setFileSize((long) CONTENT.length);
        documentFile.setResourceType(ResourceType.OFFICIAL_PUBLICATION);
        documentFile.setAccessRights(AccessRights.RESTRICTED_ACCESS);
        documentFile.setLicense(License.BY_NC);
        documentFile.setApproveStatus(ApproveStatus.APPROVED);
        documentFile.setTimestamp(LocalDateTime.now());
        documentFile.setDocument(document);
        return documentFileRepository.save(documentFile);
    }
}
