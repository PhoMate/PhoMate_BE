package barcode.phomate.domain.photo.dto;

public record PhotoUploadCommitItem(
        Long photoId,
        String originalKey,
        String etag,
        Long clientLastModifiedMs
) {}

