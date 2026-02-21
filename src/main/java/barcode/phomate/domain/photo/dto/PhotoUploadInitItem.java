package barcode.phomate.domain.photo.dto;

public record PhotoUploadInitItem(
        String originalFilename,
        String contentType,
        Long size,
        Long clientLastModifiedMs
) {}
