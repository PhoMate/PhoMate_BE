package barcode.phomate.domain.folder.dto;

import barcode.phomate.domain.folder.domain.entity.FolderRole;
import jakarta.validation.constraints.NotNull;

public record FolderInviteRequestDTO(
        @NotNull
        Long memberId,
        @NotNull
        FolderRole role
) {}
