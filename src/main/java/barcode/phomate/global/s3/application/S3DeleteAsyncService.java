package barcode.phomate.global.s3.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3DeleteAsyncService {

    private final S3StorageService s3StorageService;

    @Async("embeddingExecutor")
    public void deletePostImages(Long postId, String originalKey, String thumbKey, String previewKey) {
        int maxAttempts = 3;
        long backoffMs = 300;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                s3StorageService.deleteObject(originalKey);
                s3StorageService.deleteObject(thumbKey);
                s3StorageService.deleteObject(previewKey);

                log.info("[S3-DEL] success postId={} attempt={}", postId, attempt);
                return;

            } catch (Exception e) {
                log.warn("[S3-DEL] retryable fail postId={} attempt={}/{} err={}",
                        postId, attempt, maxAttempts, e.getMessage(), e);
            }

            if (attempt == maxAttempts) {
                log.error("[S3-DEL] permanently failed postId={} after {} attempts", postId, maxAttempts);
                return;
            }

            try { Thread.sleep(backoffMs); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            backoffMs *= 2;
        }
    }
}

