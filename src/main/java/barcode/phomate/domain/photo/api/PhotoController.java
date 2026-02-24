package barcode.phomate.domain.photo.api;

import barcode.phomate.domain.photo.application.PhotoService;
import barcode.phomate.domain.photo.dto.PhotoFeedResponseDTO;
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
        return ResponseEntity.created(URI.create("/api/photos/" + photoId)).build();
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
    @Operation(summary = "사진 휴지통 보내기", description = "사진을 휴지통으로 이동(Soft Delete)합니다. S3/벡터는 삭제하지 않습니다.")
    public ResponseEntity<Void> moveToTrash(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long photoId
    ) {
        photoService.moveToTrash(memberId, photoId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{photoId}/purge")
    @Operation(summary = "사진 완전 삭제", description = "휴지통에 있는 사진을 완전 삭제(Hard Delete)합니다. DB 삭제 후 S3/벡터 삭제를 비동기로 수행합니다.")
    public ResponseEntity<Void> purgePhoto(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long photoId
    ) {
        photoService.purgePhoto(memberId, photoId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{photoId}/restore")
    @Operation(summary = "사진 복구", description = "휴지통에 있는 사진을 복구합니다.")
    public ResponseEntity<Void> restorePhoto(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long photoId
    ) {
        photoService.restorePhoto(memberId, photoId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @Operation(summary = "내 앨범 조회(최신순 무한스크롤)", description = "shotAt desc, id desc 커서 기반 무한스크롤. 휴지통은 제외.")
    public ResponseEntity<PhotoFeedResponseDTO> getAlbumLatest(
            @AuthenticationPrincipal Long memberId,
            @RequestParam(required = false) String cursorShotAt,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(defaultValue = "20") int size
    ) {
        PhotoFeedResponseDTO res = photoService.getAlbumLatest(cursorShotAt, cursorId, size, memberId);
        return ResponseEntity.ok(res);
    }

    @GetMapping("/trash")
    @Operation(summary = "휴지통 조회(최신순 무한스크롤)", description = "deletedAt desc, id desc 커서 기반 무한스크롤.")
    public ResponseEntity<PhotoFeedResponseDTO> getTrashLatest(
            @AuthenticationPrincipal Long memberId,
            @RequestParam(required = false) String cursorDeletedAt,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(defaultValue = "20") int size
    ) {
        PhotoFeedResponseDTO res = photoService.getTrashLatest(cursorDeletedAt, cursorId, size, memberId);
        return ResponseEntity.ok(res);
    }
}
