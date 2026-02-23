package barcode.phomate.domain.folder.dto;

import barcode.phomate.domain.folder.domain.entity.FolderRole;

public record FolderMemberRoleResponseDTO(
        Long memberId,
        String memberNickName,
        FolderRole role
) {}
