package barcode.phomate.domain.folder.dto;

import barcode.phomate.domain.folder.domain.entity.FolderRole;

public record FolderInvitationResponseDTO(
        Long folderMemberId,
        Long folderId,
        String folderName,
        Long invitedByMemberIf,
        FolderRole role
) {}
