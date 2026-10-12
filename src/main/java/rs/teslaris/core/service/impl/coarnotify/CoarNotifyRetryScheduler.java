package rs.teslaris.core.service.impl.coarnotify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.teslaris.core.service.interfaces.coarnotify.CoarNotifyTransferService;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "coar-notify.retry.enabled", havingValue = "true",
    matchIfMissing = true)
public class CoarNotifyRetryScheduler {

    private final CoarNotifyTransferService coarNotifyTransferService;


    @Scheduled(fixedDelayString = "${coar-notify.retry.period-ms:60000}",
        initialDelayString = "${coar-notify.retry.initial-delay-ms:60000}")
    public void retryDueTransfers() {
        var resent = coarNotifyTransferService.retryDueTransfers();
        if (resent > 0) {
            log.info("Re-offered {} COAR Notify file transfers.", resent);
        }
    }
}
