package barcode.phomate.domain.folder.dto;

import barcode.phomate.domain.folder.domain.entity.FolderType;

public record FolderCreateRequestDTO(
        String folderName,
        FolderType type
) {}
