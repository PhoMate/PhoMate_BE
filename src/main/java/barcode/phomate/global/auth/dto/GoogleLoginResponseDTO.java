package barcode.phomate.global.auth.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GoogleLoginResponseDTO {
    private Long memberId;
    private String accessToken;
    private String refreshToken;
}
