package barcode.phomate.domain.photo.dto;

import java.util.List;

public record PhotoUploadInitRequest(
        List<PhotoUploadInitItem> items
) {}
