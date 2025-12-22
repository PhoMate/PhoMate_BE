package barcode.phomate.global.fastapi.client;

import barcode.phomate.global.fastapi.dto.SearchResponseDTO;
import barcode.phomate.global.fastapi.dto.TextSearchRequestDTO;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class SearchWorkerClient {

    private final WebClient webClient;

    public SearchWorkerClient(WebClient embeddingWorkerWebClient) {
        this.webClient = embeddingWorkerWebClient;
    }

    public SearchResponseDTO searchText(TextSearchRequestDTO req) {
        return webClient.post()
                .uri("/search/text")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(SearchResponseDTO.class)
                .block();
    }
}

