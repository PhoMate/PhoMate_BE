package barcode.phomate.domain.folder.dto;

import jakarta.validation.constraints.NotNull;

public record FolderInvitationReplyRequestDTO(
        @NotNull
        Boolean accepted
) {}
