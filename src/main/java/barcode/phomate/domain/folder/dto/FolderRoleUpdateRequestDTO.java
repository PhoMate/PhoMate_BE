package barcode.phomate.domain.folder.dto;

import barcode.phomate.domain.folder.domain.entity.FolderRole;
import jakarta.validation.constraints.NotNull;

public record FolderRoleUpdateRequestDTO(
        @NotNull FolderRole role
) {}
