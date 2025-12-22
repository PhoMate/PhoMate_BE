package barcode.phomate.global.google.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GoogleUserInfoDTO {
    private String providerId;
    private String email;
    private String name;
    private String picture;
}
