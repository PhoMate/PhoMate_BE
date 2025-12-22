package barcode.phomate.global.fastapi.client;

import barcode.phomate.global.fastapi.dto.EmbedRequestDTO;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class EmbeddingWorkerClient {

    private final WebClient webClient;

    public EmbeddingWorkerClient(WebClient embeddingWorkerWebClient) {
        this.webClient = embeddingWorkerWebClient;
    }

    public void requestPostEmbedding(EmbedRequestDTO req) {
        webClient.post()
                .uri("/jobs/post-embedding")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public void deletePostVector(Long postId) {
        webClient.delete()
                .uri("/vectors/posts/{postId}", postId)
                .retrieve()
                .toBodilessEntity()
                .block();
    }
}
