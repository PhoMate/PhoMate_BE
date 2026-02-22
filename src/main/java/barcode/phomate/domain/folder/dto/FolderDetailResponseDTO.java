package barcode.phomate.domain.folder.dto;

import barcode.phomate.domain.folder.domain.entity.Folder;
import barcode.phomate.domain.folder.domain.entity.FolderType;
import barcode.phomate.domain.folder.domain.entity.PhotoFolder;

import java.time.LocalDateTime;
import java.util.List;

public record FolderDetailResponseDTO(
        Long folderId,
        String folderName,
        FolderType type,
        LocalDateTime createdAt,
        List<PhotoInfoDTO> photos
) {
    public static FolderDetailResponseDTO from(Folder f,
                                               List<PhotoFolder> p,
                                               String cdnBaseUrl) {
        List<PhotoInfoDTO> photos = p.stream()
                .map(pf -> new PhotoInfoDTO(
                        pf.getPhoto().getId(),
                        cdnBaseUrl + "/" + pf.getPhoto().getPreviewKey(),
                        pf.getPhoto().getShotAt()
                ))
                .toList();

        return new FolderDetailResponseDTO(
                f.getId(),
                f.getFolderName(),
                f.getType(),
                f.getCreatedAt(),
                photos
        );
    }
}
