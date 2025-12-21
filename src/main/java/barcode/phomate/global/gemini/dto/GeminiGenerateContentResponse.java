package barcode.phomate.global.gemini.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

// 정의 안된 필드 무시
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class GeminiGenerateContentResponse {

    private List<Candidate> candidates;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter
    public static class Candidate {
        private Content content;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter
    public static class Content {
        private List<Part> parts;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter
    public static class Part {
        private String text;
        private InlineData inlineData;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter
    public static class InlineData {
        private String mimeType;
        private String data;
    }
}
