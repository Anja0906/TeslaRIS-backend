package rs.teslaris.core.service.interfaces.coarnotify;

import org.springframework.stereotype.Service;

@Service
public interface FileDigestService {

    String sha256Base64(String serverFilename);
}
