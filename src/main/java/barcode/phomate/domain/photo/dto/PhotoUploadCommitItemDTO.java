package barcode.phomate.domain.photo.dto;

public record PhotoUploadCommitItemDTO(
        Long photoId,
        String originalKey,
        String etag,
        Long clientLastModifiedMs
) {}

