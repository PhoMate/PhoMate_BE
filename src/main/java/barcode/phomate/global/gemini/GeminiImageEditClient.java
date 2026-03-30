package barcode.phomate.global.gemini;

import barcode.phomate.global.exception.BadRequestException;
import barcode.phomate.global.gemini.dto.GeminiGenerateContentRequest;
import barcode.phomate.global.gemini.dto.GeminiGenerateContentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiImageEditClient {

    private final WebClient geminiWebClient;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.gemini.api-key}")
    private String apiKey;

    @Value("${spring.ai.gemini.image.options.model}")
    private String imageModel;

    public byte[] edit(byte[] inputJpegBytes, String prompt) {
        try {
            String base64 = Base64.getEncoder().encodeToString(inputJpegBytes);

            GeminiGenerateContentRequest req = new GeminiGenerateContentRequest(
                    List.of(new GeminiGenerateContentRequest.Content(
                            List.of(
                                    GeminiGenerateContentRequest.Part.text(prompt),
                                    GeminiGenerateContentRequest.Part.inlineImageJpeg(base64)
                            )
                    ))
            );

            String path = "/v1beta/models/" + imageModel + ":generateContent";

            String raw = geminiWebClient.post()
                    .uri(path)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (raw == null || raw.isBlank()) {
                throw new BadRequestException("Gemini response is empty.");
            }

            GeminiGenerateContentResponse res =
                    objectMapper.readValue(raw, GeminiGenerateContentResponse.class);

            if (res.getCandidates() == null || res.getCandidates().isEmpty()) {
                throw new BadRequestException("Gemini returned no candidates.");
            }

            var parts = res.getCandidates().get(0).getContent() == null
                    ? null
                    : res.getCandidates().get(0).getContent().getParts();
            if (parts == null || parts.isEmpty()) {
                throw new BadRequestException("Gemini returned no parts.");
            }

            // 이미지(inlineData) 파트 우선 찾기
            for (var p : parts) {
                if (p.getInlineData() != null && p.getInlineData().getData() != null) {
                    return Base64.getDecoder().decode(p.getInlineData().getData());
                }
            }

            // 이미지가 없고 텍스트만 온 경우 -> 사용자 안내 가능하게 에러로 반환
            String msg = parts.stream()
                    .map(GeminiGenerateContentResponse.Part::getText)
                    .filter(t -> t != null && !t.isBlank())
                    .findFirst()
                    .orElse("Gemini did not return an image.");

            throw new BadRequestException(msg);

        } catch (BadRequestException e) {
            throw e;
        }
        catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.error("[gemini 에러] status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BadRequestException("제미나이 에러 났어요: " + e.getStatusCode() + " " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("[gemini 에러] unexpected error", e);
            throw new BadRequestException("제미나이 에러 났어요2: " + e.getMessage());
        }
    }
}
