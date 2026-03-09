package barcode.phomate.domain.chat.dto;

import barcode.phomate.domain.folder.dto.PhotoInfoDTO;

import java.util.List;

public record ChatFolderPreviewResponseDTO(
        String suggestedFolderName,
        List<PhotoInfoDTO> photos
) {
}
