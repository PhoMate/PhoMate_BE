package barcode.phomate.domain.folder.dto;

import java.time.LocalDateTime;

public record PhotoInfoDTO(
        Long photoId,
        String previewUrl,
        LocalDateTime shotAt
) {}
