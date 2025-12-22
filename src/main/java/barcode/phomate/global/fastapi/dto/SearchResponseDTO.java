package barcode.phomate.global.fastapi.dto;


import java.util.List;

public record SearchResponseDTO(
        List<SearchHitDTO> hits
) {}