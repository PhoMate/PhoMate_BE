package barcode.phomate.domain.folder.dto;

import barcode.phomate.domain.folder.domain.entity.FolderRole;
import jakarta.validation.constraints.NotNull;

public record FolderInviteRequestDTO(
        @NotNull
        String email,
        @NotNull
        FolderRole role
) {}
