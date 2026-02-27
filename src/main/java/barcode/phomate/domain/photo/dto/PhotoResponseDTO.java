package barcode.phomate.domain.photo.dto;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record PhotoResponseDTO(
        Long photoId,
        String thumbnailUrl,
        String previewUrl,
        LocalDateTime shotAt
) {
    public static PhotoResponseDTO of(Long photoId, String thumbnailUrl, String previewUrl, LocalDateTime shotAt) {
        return PhotoResponseDTO.builder()
                .photoId(photoId)
                .thumbnailUrl(thumbnailUrl)
                .previewUrl(previewUrl)
                .shotAt(shotAt)
                .build();
    }
}
