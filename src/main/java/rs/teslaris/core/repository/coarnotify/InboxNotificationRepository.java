package rs.teslaris.core.repository.coarnotify;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rs.teslaris.core.model.coarnotify.InboxNotification;

@Repository
public interface InboxNotificationRepository extends JpaRepository<InboxNotification, Integer> {

    boolean existsByNotificationId(String notificationId);
}
