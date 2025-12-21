package barcode.phomate.domain.edit.dto;

import barcode.phomate.domain.edit.domain.entity.EditVersion;

public class EditVersionMapper {

    private EditVersionMapper() {}

    public static EditVersionResponseDTO toDto(EditVersion v, String cloudFrontBaseUrl) {
        EditVersionResponseDTO dto = new EditVersionResponseDTO();
        dto.setEditSessionId(v.getEditSession().getId());
        dto.setEditVersionId(v.getId());
        dto.setVersionIndex(v.getVersionIndex());
        dto.setS3Key(v.getS3Key());
        dto.setImageUrl(cloudFrontBaseUrl + "/" + v.getS3Key());
        dto.setSourceType(v.getSourceType().name());
        return dto;
    }
}
