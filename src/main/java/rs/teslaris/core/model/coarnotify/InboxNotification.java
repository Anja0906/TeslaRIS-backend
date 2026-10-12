package rs.teslaris.core.model.coarnotify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;
import rs.teslaris.core.model.commontypes.BaseEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "coar_notify_inbox")
@SQLRestriction("deleted=false")
public class InboxNotification extends BaseEntity {

    @Column(name = "notification_id", nullable = false, unique = true)
    private String notificationId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "in_reply_to")
    private String inReplyTo;

    @Column(name = "origin_id", nullable = false)
    private String originId;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "remote_address")
    private String remoteAddress;

    @Column(name = "processed", nullable = false)
    private boolean processed;

    @Column(name = "processing_note", length = 2000)
    private String processingNote;
}
