package barcode.phomate.domain.chat.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatSendResponseDTO {

    private Long chatSessionId;

    private Long userMessageId;
    private Long assistantMessageId;

    private String assistantContent;

    private String editedUrl;

    public static ChatSendResponseDTO of(
            Long chatSessionId,
            Long userMessageId,
            Long assistantMessageId,
            String assistantContent,
            String editedUrl
    ) {
        ChatSendResponseDTO dto = new ChatSendResponseDTO();
        dto.setChatSessionId(chatSessionId);
        dto.setUserMessageId(userMessageId);
        dto.setAssistantMessageId(assistantMessageId);
        dto.setAssistantContent(assistantContent);
        dto.setEditedUrl(editedUrl);
        return dto;
    }
}
