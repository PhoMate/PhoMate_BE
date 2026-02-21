package barcode.phomate.domain.photo.dto;

public record PhotoUploadCommitResult(
        Long photoId,
        String previewUrl
) {}
