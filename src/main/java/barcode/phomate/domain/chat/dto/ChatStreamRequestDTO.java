package barcode.phomate.domain.chat.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ChatStreamRequestDTO {

    private Long memberId;

    private Long chatSessionId;

    private String userText;
}
