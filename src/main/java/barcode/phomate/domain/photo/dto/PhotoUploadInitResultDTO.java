package barcode.phomate.domain.photo.dto;

public record PhotoUploadInitResultDTO(
        Long photoId,
        String originalKey,
        String uploadUrl,
        Long expiresAtMs
) {}
