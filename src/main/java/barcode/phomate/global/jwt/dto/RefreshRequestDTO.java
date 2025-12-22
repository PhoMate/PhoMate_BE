package barcode.phomate.global.jwt.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RefreshRequestDTO {
    private String refreshToken;
}
