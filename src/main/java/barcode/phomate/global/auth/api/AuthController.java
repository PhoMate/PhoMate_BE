package barcode.phomate.global.auth.api;

import barcode.phomate.global.auth.application.AuthService;
import barcode.phomate.global.auth.dto.GoogleLoginRequestDTO;
import barcode.phomate.global.auth.dto.GoogleLoginResponseDTO;
import barcode.phomate.global.jwt.dto.RefreshRequestDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
@Tag(name = "Auth API", description = "Auth API입니다.")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/google")
    @Operation(summary = "구글 로그인", description = "구글 로그인 API")
    public ResponseEntity<GoogleLoginResponseDTO> googleLogin(@RequestBody GoogleLoginRequestDTO request){
        GoogleLoginResponseDTO response = authService.loginWithGoogle(request);
        return ResponseEntity.ok(response);
    }


    @PostMapping("/reissue")
    @Operation(summary = "토큰 재발급", description = "토큰 재발급 API")
    public ResponseEntity<GoogleLoginResponseDTO> reissue(@RequestBody RefreshRequestDTO request){
        GoogleLoginResponseDTO response =  authService.reissue(request);
        return ResponseEntity.ok(response);
    }

}
