package barcode.phomate.global.fastapi.application;

import barcode.phomate.global.fastapi.EmbeddingWorkerClient;
import barcode.phomate.global.fastapi.dto.EmbedRequestDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingAsyncService {

    private final EmbeddingWorkerClient embeddingWorkerClient;

    @Async("embeddingExecutor")
    public void embedPost(EmbedRequestDTO req) {
        int maxAttempts = 3;
        long backoffMs = 300;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                embeddingWorkerClient.requestPostEmbedding(req);
                log.info("[EMBED] success postId={} attempt={}", req.postId(), attempt);
                return;

            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();

                if (status >= 400 && status < 500) {
                    log.error("[EMBED] failed(postId={}) 4xx status={} body={}",
                            req.postId(), status, safeBody(e));
                    return;
                }

                log.warn("[EMBED] retryable fail(postId={}) status={} attempt={}/{}",
                        req.postId(), status, attempt, maxAttempts, e);

            } catch (Exception e) {
                log.warn("[EMBED] retryable fail(postId={}) attempt={}/{} err={}",
                        req.postId(), attempt, maxAttempts, e.getMessage(), e);
            }

            if (attempt == maxAttempts) {
                log.error("[EMBED] permanently failed postId={} after {} attempts",
                        req.postId(), maxAttempts);
                return;
            }

            // 백오프
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            backoffMs *= 2;
        }
    }


    @Async("embeddingExecutor")
    public void deletePostVector(Long postId) {
        int maxAttempts = 3;
        long backoffMs = 300;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                embeddingWorkerClient.deletePostVector(postId);
                log.info("[VEC-DEL] success postId={} attempt={}", postId, attempt);
                return;

            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();

                if (status == 404) {
                    log.info("[VEC-DEL] already deleted postId={} (404)", postId);
                    return;
                }

                if (status >= 400 && status < 500) {
                    log.error("[VEC-DEL] failed postId={} 4xx status={} body={}",
                            postId, status, safeBody(e));
                    return;
                }

                log.warn("[VEC-DEL] retryable fail postId={} status={} attempt={}/{}",
                        postId, status, attempt, maxAttempts, e);

            } catch (Exception e) {
                log.warn("[VEC-DEL] retryable fail postId={} attempt={}/{} err={}",
                        postId, attempt, maxAttempts, e.getMessage(), e);
            }

            if (attempt == maxAttempts) {
                log.error("[VEC-DEL] permanently failed postId={} after {} attempts",
                        postId, maxAttempts);
                return;
            }

            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            backoffMs *= 2;
        }
    }

    private String safeBody(WebClientResponseException e) {
        try {
            String body = e.getResponseBodyAsString();
            if (body == null) return "";
            return body.length() > 300 ? body.substring(0, 300) + "..." : body;
        } catch (Exception ignore) {
            return "";
        }
    }
}

