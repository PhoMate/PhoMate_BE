package barcode.phomate.domain.edit.api;

import barcode.phomate.domain.edit.application.EditService;
import barcode.phomate.domain.edit.domain.entity.EditSession;
import barcode.phomate.domain.edit.domain.entity.EditVersion;
import barcode.phomate.domain.edit.dto.EditVersionMapper;
import barcode.phomate.domain.edit.dto.EditVersionResponseDTO;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/edits")
public class EditController {

    private final EditService editService;

    @Value("${app.cdn.base-url}")
    private String cloudFrontBaseUrl;

    @PostMapping("/start")
    @Operation(summary = "편집 세션 시작", description = "EditSession 시작")
    public ResponseEntity<Long> start(
            @RequestParam Long memberId,
            @RequestParam Long postId
    ) {
        EditSession session = editService.start(memberId, postId);
        return ResponseEntity.ok(session.getId());
    }

    @GetMapping("/{editSessionId}/current")
    @Operation(summary = "현재 버전 조회", description = "현재 currentIndex가 가리키는 버전 정보를 가져옵니다.")
    public ResponseEntity<EditVersionResponseDTO> current(
            @RequestParam Long memberId,
            @PathVariable Long editSessionId
    ) {
        EditVersion version = editService.getCurrentVersion(memberId, editSessionId);

        return ResponseEntity.ok(
                EditVersionMapper.toDto(version, cloudFrontBaseUrl)
        );
    }

    @PostMapping("/{editSessionId}/chat")
    @Operation(summary = "챗봇 편집", description = "나노바나나로 편집해 새 버전 생성")
    public ResponseEntity<EditVersionResponseDTO> chatEdit(
            @RequestParam Long memberId,
            @PathVariable Long editSessionId,
            @RequestBody String prompt
    ) {
        EditVersion version = editService.chatEdit(memberId, editSessionId, prompt);

        return ResponseEntity.ok(
                EditVersionMapper.toDto(version, cloudFrontBaseUrl)
        );
    }

    @PostMapping(value = "/{editSessionId}/direct", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "직접 편집 업로드", description = "ToastUI+Konva 결과 파일 업로드로 새 버전 생성")
    public ResponseEntity<EditVersionResponseDTO> directUpload(
            @RequestParam Long memberId,
            @PathVariable Long editSessionId,
            @RequestPart("file") MultipartFile file
    ) {
        EditVersion version = editService.directUpload(memberId, editSessionId, file);

        return ResponseEntity.ok(
                EditVersionMapper.toDto(version, cloudFrontBaseUrl)
        );    }

    @PostMapping("/{editSessionId}/undo")
    @Operation(summary = "undo", description = "currentIndex -1")
    public ResponseEntity<EditVersionResponseDTO> undo(
            @RequestParam Long memberId,
            @PathVariable Long editSessionId
    ) {
        EditVersion version = editService.undo(memberId, editSessionId);

        return ResponseEntity.ok(
                EditVersionMapper.toDto(version, cloudFrontBaseUrl)
        );    }

    @PostMapping("/{editSessionId}/redo")
    @Operation(summary = "redo", description = "currentIndex +1")
    public ResponseEntity<EditVersionResponseDTO> redo(
            @RequestParam Long memberId,
            @PathVariable Long editSessionId
    ) {
        EditVersion version = editService.redo(memberId, editSessionId);

        return ResponseEntity.ok(
                EditVersionMapper.toDto(version, cloudFrontBaseUrl)
        );    }

    @PostMapping("/{editSessionId}/finalize")
    @Operation(summary = "최종 저장 + 정리", description = "나머지 버전들을 S3에서 삭제")
    public ResponseEntity<String> finalizeAndCleanup(
            @RequestParam Long memberId,
            @PathVariable Long editSessionId
    ) {
        return ResponseEntity.ok(editService.finalizeAndCleanup(memberId, editSessionId));
    }

    @DeleteMapping("/{editSessionId}")
    @Operation(summary = "편집 취소 + 정리", description = "세션 취소 후 생성된 버전들을 S3에서 삭제")
    public ResponseEntity<Void> cancel(
            @RequestParam Long memberId,
            @PathVariable Long editSessionId
    ) {
        editService.cancelAndCleanup(memberId, editSessionId);
        return ResponseEntity.noContent().build();
    }
}
