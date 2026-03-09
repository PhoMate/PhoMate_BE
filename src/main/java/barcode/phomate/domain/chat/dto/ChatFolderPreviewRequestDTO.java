package barcode.phomate.domain.chat.dto;

import lombok.Getter;

public record ChatFolderPreviewRequestDTO (
    Long chatSessionId,
    String userText,
    Integer topK
) {}
