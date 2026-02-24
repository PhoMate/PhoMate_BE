package barcode.phomate.domain.photo.dto;

import java.util.List;

public record PhotoUploadInitRequestDTO(
        List<PhotoUploadInitItemDTO> items
) {}
