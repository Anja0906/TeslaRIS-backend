package rs.teslaris.core.service.impl.coarnotify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.teslaris.core.annotation.Traceable;
import rs.teslaris.core.model.coarnotify.FileTransfer;
import rs.teslaris.core.model.coarnotify.FileTransferStatus;
import rs.teslaris.core.model.coarnotify.InboxNotification;
import rs.teslaris.core.repository.coarnotify.FileTransferRepository;
import rs.teslaris.core.repository.coarnotify.InboxNotificationRepository;
import rs.teslaris.core.service.interfaces.coarnotify.CoarNotifyInboxService;

@Service
@RequiredArgsConstructor
@Slf4j
@Traceable
public class CoarNotifyInboxServiceImpl implements CoarNotifyInboxService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final InboxNotificationRepository inboxNotificationRepository;

    private final FileTransferRepository fileTransferRepository;

    @Value("${coar-notify.origin.id}")
    private String originId;


    @Override
    @Transactional
    public InboxResult receiveNotification(String rawBody, String remoteAddress) {
        JsonNode notification;
        try {
            notification = MAPPER.readTree(rawBody);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return refuse(HttpStatus.BAD_REQUEST, "Body is not JSON.", remoteAddress);
        }
        if (Objects.isNull(notification) || !notification.isObject()) {
            return refuse(HttpStatus.BAD_REQUEST, "Body is not a JSON object.", remoteAddress);
        }

        var id = getText(notification, "id");
        var types = getTypes(notification.path("type"));
        var senderId = getText(notification.path("origin"), "id");
        var targetId = getText(notification.path("target"), "id");
        if (Objects.isNull(id) || types.isEmpty() || Objects.isNull(senderId) ||
            Objects.isNull(targetId)) {
            return refuse(HttpStatus.UNPROCESSABLE_ENTITY,
                "id, type, origin.id and target.id are required.", remoteAddress);
        }
        if (!isAbsoluteUri(id) || !isAbsoluteUri(senderId) || !isAbsoluteUri(targetId)) {
            return refuse(HttpStatus.UNPROCESSABLE_ENTITY,
                "id, origin.id and target.id must be absolute URIs.", remoteAddress);
        }
        if (!targetId.equals(originId)) {
            return refuse(HttpStatus.FORBIDDEN, "target.id is not " + originId + ".",
                remoteAddress);
        }
        if (inboxNotificationRepository.existsByNotificationId(id)) {
            log.info("Duplicate COAR Notify notification {} ignored.", id);
            return new InboxResult(HttpStatus.ACCEPTED, "Duplicate notification.");
        }

        var isAccept = types.contains("Accept");
        if (!isAccept && !types.contains("Reject")) {
            saveNotification(notification, rawBody, String.join(",", types), remoteAddress, false,
                "Type not handled.");
            return new InboxResult(HttpStatus.ACCEPTED, "Stored, type not handled.");
        }
        var type = isAccept ? "Accept" : "Reject";

        var inReplyTo = getText(notification, "inReplyTo");
        if (Objects.isNull(inReplyTo)) {
            return refuse(HttpStatus.UNPROCESSABLE_ENTITY, "inReplyTo is required.",
                remoteAddress);
        }

        var match = fileTransferRepository.findByNotificationId(inReplyTo);
        if (match.isEmpty()) {
            return refuse(HttpStatus.FORBIDDEN, "inReplyTo does not match a transfer.",
                remoteAddress);
        }

        var transfer = match.get();
        if (!senderId.equals(transfer.getTargetId())) {
            return refuse(HttpStatus.FORBIDDEN,
                "origin.id is not the repository the transfer was sent to.", remoteAddress);
        }

        if (transfer.getStatus() == FileTransferStatus.ACCEPTED ||
            transfer.getStatus() == FileTransferStatus.REJECTED) {
            saveNotification(notification, rawBody, type, remoteAddress, false,
                "Transfer " + transfer.getId() + " was already " + transfer.getStatus() + ".");
            return new InboxResult(HttpStatus.ACCEPTED, "Stored, transfer already answered.");
        }

        var note = isAccept ? applyAccept(transfer, notification) :
            applyReject(transfer, notification);
        fileTransferRepository.save(transfer);
        saveNotification(notification, rawBody, type, remoteAddress, true, note);

        log.info("COAR Notify {} for transfer {} from {}.", type, transfer.getId(), senderId);
        return new InboxResult(HttpStatus.ACCEPTED, note);
    }

    private String applyAccept(FileTransfer transfer, JsonNode notification) {
        var context = notification.path("context");
        transfer.setStatus(FileTransferStatus.ACCEPTED);
        transfer.setRemoteItemUrl(getText(context, "id"));
        transfer.setRemoteCiteAs(getText(context, "ietf:cite-as"));
        transfer.setLastError(null);

        return "Transfer " + transfer.getId() + " accepted as " + transfer.getRemoteItemUrl() + ".";
    }

    private String applyReject(FileTransfer transfer, JsonNode notification) {
        var summary = getText(notification, "summary");
        transfer.setStatus(FileTransferStatus.REJECTED);
        transfer.setLastError(Objects.nonNull(summary) ? StringUtils.abbreviate(summary, 2000) :
            "Rejected without summary.");

        return "Transfer " + transfer.getId() + " rejected: " + transfer.getLastError();
    }

    private void saveNotification(JsonNode notification, String rawBody, String type,
                                  String remoteAddress, boolean processed, String note) {
        var inboxNotification = new InboxNotification();
        inboxNotification.setNotificationId(getText(notification, "id"));
        inboxNotification.setType(type);
        inboxNotification.setInReplyTo(getText(notification, "inReplyTo"));
        inboxNotification.setOriginId(getText(notification.path("origin"), "id"));
        inboxNotification.setRawPayload(rawBody);
        inboxNotification.setReceivedAt(Instant.now());
        inboxNotification.setRemoteAddress(remoteAddress);
        inboxNotification.setProcessed(processed);
        inboxNotification.setProcessingNote(StringUtils.abbreviate(note, 2000));
        inboxNotificationRepository.save(inboxNotification);
    }

    private InboxResult refuse(HttpStatus status, String reason, String remoteAddress) {
        log.warn("COAR Notify notification from {} refused: {}", remoteAddress, reason);
        return new InboxResult(status, reason);
    }

    private String getText(JsonNode node, String field) {
        var value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private Set<String> getTypes(JsonNode type) {
        var types = new HashSet<String>();
        if (type.isTextual()) {
            types.add(type.asText());
        } else if (type.isArray()) {
            type.forEach(value -> {
                if (value.isTextual()) {
                    types.add(value.asText());
                }
            });
        }
        return types;
    }

    private boolean isAbsoluteUri(String value) {
        try {
            return new URI(value).isAbsolute();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
