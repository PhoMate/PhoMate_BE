package barcode.phomate.global.fastapi;

import barcode.phomate.global.fastapi.dto.EmbedRequestDTO;
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

    public void requestPostEmbedding(EmbedRequestDTO req) {
        webClient.post()
                .uri("/jobs/post-embedding")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

}
