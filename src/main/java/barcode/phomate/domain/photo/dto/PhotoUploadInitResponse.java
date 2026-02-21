package barcode.phomate.domain.photo.dto;

import java.util.List;

public record PhotoUploadInitResponse(
        List<PhotoUploadInitResult> items
) {}
