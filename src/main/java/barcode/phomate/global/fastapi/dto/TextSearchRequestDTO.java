package barcode.phomate.global.fastapi.dto;

public record TextSearchRequestDTO(
        String query,
        Integer topK,
        Long memberId,
        Long createdAfterMs,
        Long createdBeforeMs
) {}
