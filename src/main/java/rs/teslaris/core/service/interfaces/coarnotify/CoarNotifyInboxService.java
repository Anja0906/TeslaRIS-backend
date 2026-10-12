package rs.teslaris.core.service.interfaces.coarnotify;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public interface CoarNotifyInboxService {

    InboxResult receiveNotification(String rawBody, String remoteAddress);

    record InboxResult(HttpStatus status, String message) {
    }
}
