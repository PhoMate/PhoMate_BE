package barcode.phomate.domain.chat.dto;

import lombok.Getter;

@Getter
public class ChatSearchStreamRequestDTO {
    private Long chatSessionId;

    private String userText;
}
