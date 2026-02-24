package barcode.phomate.domain.photo.dto;

import java.util.List;

public record PhotoUploadCommitRequestDTO(
        List<PhotoUploadCommitItemDTO> items
) {}
