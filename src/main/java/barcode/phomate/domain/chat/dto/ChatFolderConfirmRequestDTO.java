package barcode.phomate.domain.chat.dto;

import java.util.List;

public record ChatFolderConfirmRequestDTO(
        boolean accepted,
        String folderName,
        List<Long> photoIds
) {
}
