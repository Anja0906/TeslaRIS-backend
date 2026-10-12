package rs.teslaris.core.model.coarnotify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;
import rs.teslaris.core.model.commontypes.BaseEntity;
import rs.teslaris.core.model.document.DocumentFile;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "coar_notify_file_transfers", indexes = {
    @Index(name = "idx_coar_transfer_notification", columnList = "notification_id", unique = true),
    @Index(name = "idx_coar_transfer_status", columnList = "status, last_attempt_at")
})
@SQLRestriction("deleted=false")
public class FileTransfer extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_file_id", nullable = false)
    private DocumentFile documentFile;

    @Column(name = "notification_id", nullable = false, unique = true)
    private String notificationId;

    @Column(name = "target_id", nullable = false)
    private String targetId;

    @Column(name = "target_inbox", nullable = false)
    private String targetInbox;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FileTransferStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "sha256", nullable = false)
    private String sha256;

    @Column(name = "token_expires_at", nullable = false)
    private Instant tokenExpiresAt;

    @Column(name = "remote_item_url")
    private String remoteItemUrl;

    @Column(name = "remote_cite_as")
    private String remoteCiteAs;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "downloaded_at")
    private Instant downloadedAt;

    @Column(name = "downloaded_from_ip")
    private String downloadedFromIp;
}
