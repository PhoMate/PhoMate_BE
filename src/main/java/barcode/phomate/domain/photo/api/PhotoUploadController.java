package barcode.phomate.domain.photo.api;

import barcode.phomate.domain.photo.application.PhotoUploadService;
import barcode.phomate.domain.photo.dto.PhotoUploadCommitRequestDTO;
import barcode.phomate.domain.photo.dto.PhotoUploadCommitResponseDTO;
import barcode.phomate.domain.photo.dto.PhotoUploadInitRequestDTO;
import barcode.phomate.domain.photo.dto.PhotoUploadInitResponseDTO;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/photos/upload")
public class PhotoUploadController {

    private final PhotoUploadService photoUploadService;

    @PostMapping("/init")
    @Operation(
            summary = "사진 업로드 init",
            description = " presigned PUT URL을 발급 (items[] 단위)"
    )
    public ResponseEntity<PhotoUploadInitResponseDTO> init(
            @AuthenticationPrincipal Long memberId,
            @RequestBody PhotoUploadInitRequestDTO request
    ) {
        return ResponseEntity.ok(photoUploadService.init(memberId, request));
    }

    @PostMapping("/commit")
    @Operation(
            summary = "사진 업로드 commit",
            description = "S3 업로드 완료 후 HEAD/ETag 검증, 썸네일/프리뷰 생성, DB 반영, 임베딩 트리거"
    )
    public ResponseEntity<PhotoUploadCommitResponseDTO> commit(
            @AuthenticationPrincipal Long memberId,
            @RequestBody PhotoUploadCommitRequestDTO request
    ) {
        return ResponseEntity.ok(photoUploadService.commit(memberId, request));
    }
}
