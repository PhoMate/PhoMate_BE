package barcode.phomate.domain.chat.dto;

public record ChatFolderPreviewRequestDTO (
    Long chatSessionId,
    String userText,
    Integer topK
) {}
