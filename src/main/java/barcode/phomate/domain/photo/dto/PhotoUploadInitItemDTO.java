package barcode.phomate.domain.photo.dto;

public record PhotoUploadInitItemDTO(
        String originalFilename,
        String contentType,
        Long size,
        Long clientLastModifiedMs
) {}
