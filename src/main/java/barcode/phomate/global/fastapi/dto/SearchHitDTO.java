package barcode.phomate.global.fastapi.dto;

public record SearchHitDTO(
        Long postId,
        Double score,
        Long memberId,
        Long createdAtMs,
        String source
) {}
