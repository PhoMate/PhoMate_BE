package barcode.phomate.global.gemini.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GeminiGenerateContentRequest {

    private List<Content> contents;

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    public static class Content {
        private List<Part> parts;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    public static class Part {
        private String text;
        private InlineData inlineData;

        public static Part text(String text) {
            Part p = new Part();
            p.text = text;
            return p;
        }

        public static Part inlineImageJpeg(String base64) {
            Part p = new Part();
            p.inlineData = new InlineData("image/jpeg", base64);
            return p;
        }
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    public static class InlineData {
        private String mimeType;
        private String data;
    }
}
