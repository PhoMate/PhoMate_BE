package barcode.phomate.domain.photo.dto;

import java.util.List;

public record PhotoUploadCommitResponse(
        List<PhotoUploadCommitResult> items
) {}
