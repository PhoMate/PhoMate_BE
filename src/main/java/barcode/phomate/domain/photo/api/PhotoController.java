package barcode.phomate.domain.photo.api;

import barcode.phomate.domain.photo.application.PhotoService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/photos")
public class PhotoController {

    private final PhotoService photoService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "사진 업로드", description = "사진 업로드 API")
    public ResponseEntity<Void> createPhoto(
            @AuthenticationPrincipal Long memberId,
            @RequestPart("image") MultipartFile image,
            @RequestPart(value = "clientLastModifiedMs", required = false) Long clientLastModifiedMs
    ) {
        Long photoId = photoService.createPhoto(memberId, image, clientLastModifiedMs);
        return ResponseEntity.created(URI.create("/photos/" + photoId)).build();
    }

    @PatchMapping(value = "/{photoId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "사진 수정(이미지 교체)", description = "사진 이미지 교체 API")
    public ResponseEntity<Void> updatePhoto(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long photoId,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestPart(value = "clientLastModifiedMs", required = false) Long clientLastModifiedMs
    ) {
        photoService.updatePhoto(memberId, photoId, image, clientLastModifiedMs);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{photoId}")
    @Operation(summary = "사진 삭제", description = "사진 삭제 API")
    public ResponseEntity<Void> deletePhoto(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long photoId
    ) {
        photoService.deletePhoto(memberId, photoId);
        return ResponseEntity.noContent().build();
    }
}
