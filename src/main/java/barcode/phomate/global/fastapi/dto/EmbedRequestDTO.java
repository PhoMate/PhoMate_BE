package barcode.phomate.global.fastapi.dto;

public record EmbedRequestDTO(
        Long postId,
        Long memberId,
        String imageUrl,
        String text,
        Long createdAtMs
) {
}

