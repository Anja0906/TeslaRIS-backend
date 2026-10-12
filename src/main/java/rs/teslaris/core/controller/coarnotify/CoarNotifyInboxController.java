package rs.teslaris.core.controller.coarnotify;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import rs.teslaris.core.annotation.Traceable;
import rs.teslaris.core.service.interfaces.coarnotify.CoarNotifyInboxService;

@RestController
@RequestMapping("/api/coar-notify/inbox")
@RequiredArgsConstructor
@Traceable
public class CoarNotifyInboxController {

    private static final String LD_JSON = "application/ld+json";

    private final CoarNotifyInboxService coarNotifyInboxService;


    @PostMapping(consumes = {LD_JSON, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<String> receiveNotification(@RequestBody String body,
                                                      HttpServletRequest request) {
        var result = coarNotifyInboxService.receiveNotification(body, request.getRemoteAddr());
        return ResponseEntity.status(result.status())
            .contentType(MediaType.TEXT_PLAIN)
            .body(result.message());
    }

    @RequestMapping(method = RequestMethod.OPTIONS)
    public ResponseEntity<Void> getInboxOptions() {
        return ResponseEntity.ok()
            .allow(HttpMethod.OPTIONS, HttpMethod.POST)
            .header("Accept-Post", LD_JSON)
            .build();
    }
}
