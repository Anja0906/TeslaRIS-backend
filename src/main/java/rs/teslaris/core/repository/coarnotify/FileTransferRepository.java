package rs.teslaris.core.repository.coarnotify;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import rs.teslaris.core.model.coarnotify.FileTransfer;
import rs.teslaris.core.model.coarnotify.FileTransferStatus;

@Repository
public interface FileTransferRepository extends JpaRepository<FileTransfer, Integer> {

    @Query("SELECT t FROM FileTransfer t WHERE t.documentFile.id = :documentFileId ORDER BY t.id DESC")
    List<FileTransfer> findAllForDocumentFile(Integer documentFileId);

    @Query("SELECT t FROM FileTransfer t WHERE t.documentFile.serverFilename = :serverFilename " +
        "AND t.tokenExpiresAt = :tokenExpiresAt")
    List<FileTransfer> findAllForDownload(String serverFilename, Instant tokenExpiresAt);

    @Query("SELECT COUNT(t) > 0 FROM FileTransfer t WHERE t.documentFile.id = :documentFileId " +
        "AND t.targetInbox = :targetInbox AND t.status IN :statuses")
    boolean existsInStatus(Integer documentFileId, String targetInbox,
                           Collection<FileTransferStatus> statuses);
}
