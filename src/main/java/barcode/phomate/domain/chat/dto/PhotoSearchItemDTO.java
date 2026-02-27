package barcode.phomate.domain.chat.dto;

public record PhotoSearchItemDTO(
        Long photoId,
        String thumbnailUrl,
        String previewUrl,
        String shotAt,
        String description
) {}

