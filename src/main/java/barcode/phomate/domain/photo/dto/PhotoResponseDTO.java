package barcode.phomate.domain.photo.dto;

import lombok.Builder;

@Builder
public record PhotoResponseDTO(
        Long photoId,
        String thumbnailUrl,
        String previewUrl,
        String shotAt
) {
    public static PhotoResponseDTO of(Long photoId, String thumbnailUrl, String previewUrl, String shotAt) {
        return PhotoResponseDTO.builder()
                .photoId(photoId)
                .thumbnailUrl(thumbnailUrl)
                .previewUrl(previewUrl)
                .shotAt(shotAt)
                .build();
    }
}
