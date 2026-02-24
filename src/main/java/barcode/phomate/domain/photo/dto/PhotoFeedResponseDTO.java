package barcode.phomate.domain.photo.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record PhotoFeedResponseDTO(
        List<PhotoResponseDTO> items,
        Cursor nextCursor,
        boolean hasNext
) {
    @Builder
    public record Cursor(String cursorShotAt, Long cursorId) {
        public static Cursor latest(String cursorShotAt, Long cursorId) {
            return Cursor.builder().cursorShotAt(cursorShotAt).cursorId(cursorId).build();
        }
    }

    public static PhotoFeedResponseDTO empty() {
        return PhotoFeedResponseDTO.builder()
                .items(List.of())
                .nextCursor(null)
                .hasNext(false)
                .build();
    }

    public static PhotoFeedResponseDTO of(List<PhotoResponseDTO> items, Cursor nextCursor, boolean hasNext) {
        return PhotoFeedResponseDTO.builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }
}
