package barcode.phomate.domain.edit.dto;

import barcode.phomate.domain.edit.domain.entity.EditVersion;

public class EditVersionMapper {

    private EditVersionMapper() {}

    public static EditVersionResponseDTO toDto(EditVersion version, String cloudFrontBaseUrl) {
        EditVersionResponseDTO response = new EditVersionResponseDTO();
        response.setEditSessionId(version.getEditSession().getId());
        response.setEditVersionId(version.getId());
        response.setVersionIndex(version.getVersionIndex());
        response.setS3Key(version.getS3Key());
        response.setImageUrl(cloudFrontBaseUrl + "/" + version.getS3Key());
        response.setSourceType(version.getSourceType().name());
        return response;
    }
}
