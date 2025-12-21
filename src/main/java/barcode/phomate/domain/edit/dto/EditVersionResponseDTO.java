package barcode.phomate.domain.edit.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EditVersionResponseDTO {

    private Long editSessionId;
    private Long editVersionId;

    private int versionIndex;

    private String s3Key;       // S3 key
    private String imageUrl;    // CloudFront URL

    private String sourceType;  // CHAT / DIRECT / INITIAL
}
