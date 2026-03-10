package barcode.phomate.domain.chat.dto;

import lombok.Getter;

import java.util.List;

public record ChatFolderConfirmRequestDTO(
        boolean accepted,
        String folderName,
        List<Long> photoIds
) {
}
