package rs.teslaris.core.service.impl.coarnotify;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import rs.teslaris.core.service.interfaces.coarnotify.TransferTokenService;

@Service
@Slf4j
public class TransferTokenServiceImpl implements TransferTokenService {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;


    public TransferTokenServiceImpl(@Value("${coar-notify.token.secret:}") String secret) {
        if (Objects.isNull(secret) || secret.isBlank()) {
            log.warn("coar-notify.token.secret is not set, using a random secret. " +
                "Transfer tokens become invalid after a restart.");
            this.secret = new byte[32];
            new SecureRandom().nextBytes(this.secret);
        } else {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public String mint(Integer transferId, String serverFilename, Instant expires) {
        return HexFormat.of().formatHex(hmac(transferId, serverFilename, expires));
    }

    @Override
    public boolean matches(String token, Integer transferId, String serverFilename,
                           Instant expires) {
        if (Objects.isNull(token)) {
            return false;
        }

        try {
            return MessageDigest.isEqual(HexFormat.of().parseHex(token),
                hmac(transferId, serverFilename, expires));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] hmac(Integer transferId, String serverFilename, Instant expires) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            var message = transferId + "|" + serverFilename + "|" + expires.getEpochSecond();
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
