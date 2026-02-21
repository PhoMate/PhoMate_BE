package barcode.phomate.domain.photo.dto;

public record PhotoUploadInitResult(
        Long photoId,
        String originalKey,
        String uploadUrl,
        Long expiresAtMs
) {}
