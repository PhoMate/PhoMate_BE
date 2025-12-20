package barcode.phomate.global.fastapi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class EmbeddingWorkerClient {

    private final WebClient webClient;

    public EmbeddingWorkerClient(@Value("${embedding-worker.base-url}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public void requestPostEmbedding(EmbedRequest req) {
        webClient.post()
                .uri("/jobs/post-embedding")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public record EmbedRequest(
            Long postId,
            Long memberId,
            String imageUrl,
            String text,
            Long createdAtMs
    ) {}
}
