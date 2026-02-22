package barcode.phomate.domain.folder.dto;

import barcode.phomate.domain.folder.domain.entity.Folder;
import barcode.phomate.domain.folder.domain.entity.FolderType;

import java.time.LocalDateTime;

public record FolderResponseDTO(
        Long folderId,
        String folderName,
        FolderType type,
        LocalDateTime createdAt
) {
    public static FolderResponseDTO from(Folder f) {
        return new FolderResponseDTO(
                f.getId(),
                f.getFolderName(),
                f.getType(),
                f.getCreatedAt()
        );
    }
}
