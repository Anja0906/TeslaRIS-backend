package rs.teslaris.core.service.impl.coarnotify;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import rs.teslaris.core.service.interfaces.coarnotify.FileDigestService;
import rs.teslaris.core.service.interfaces.document.FileService;
import rs.teslaris.core.util.exceptionhandling.exception.StorageException;

@Service
@RequiredArgsConstructor
public class FileDigestServiceImpl implements FileDigestService {

    private final FileService fileService;


    @Override
    public String sha256Base64(String serverFilename) {
        try (var file = fileService.loadAsResource(serverFilename)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[8192];
            int read;
            while ((read = file.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (IOException e) {
            throw new StorageException("Could not read file " + serverFilename + ".");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
