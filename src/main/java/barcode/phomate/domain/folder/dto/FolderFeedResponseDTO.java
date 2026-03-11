package barcode.phomate.domain.folder.dto;

import barcode.phomate.domain.post.dto.PostFeedResponseDTO.Cursor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder
public record FolderFeedResponseDTO(
        List<FolderResponseDTO> items,
        Cursor nextCursor,
        boolean hasNext
) {
    @Builder
    public record Cursor(LocalDateTime cursorCreatedAt, Long cursorId) {}

    public static FolderFeedResponseDTO empty() {
        return FolderFeedResponseDTO.builder()
                .items(List.of())
                .nextCursor(null)
                .hasNext(false)
                .build();
    }

    public static FolderFeedResponseDTO of(List<FolderResponseDTO> items, Cursor nextCursor, boolean hasNext) {
        return FolderFeedResponseDTO.builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }
}
