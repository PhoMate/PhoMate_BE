package barcode.phomate.domain.photo.dto;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record PhotoDetailResponseDTO(
        Long photoId,
        String originalUrl,
        LocalDateTime shotAt
) {
    public static PhotoDetailResponseDTO of(Long photoId, String originalUrl, LocalDateTime shotAt) {
        return PhotoDetailResponseDTO.builder()
                .photoId(photoId)
                .originalUrl(originalUrl)
                .shotAt(shotAt)
                .build();
    }
}

