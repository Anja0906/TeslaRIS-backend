package rs.teslaris.core.service.interfaces.coarnotify;

import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public interface TransferTokenService {

    String mint(Integer transferId, String serverFilename, Instant expires);

    boolean matches(String token, Integer transferId, String serverFilename, Instant expires);
}
