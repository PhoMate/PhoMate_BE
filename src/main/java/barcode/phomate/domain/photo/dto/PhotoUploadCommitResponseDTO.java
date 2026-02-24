package barcode.phomate.domain.photo.dto;

import java.util.List;

public record PhotoUploadCommitResponseDTO(
        List<PhotoUploadCommitResultDTO> items
) {}
