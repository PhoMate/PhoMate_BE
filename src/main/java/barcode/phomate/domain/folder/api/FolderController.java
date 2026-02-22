package barcode.phomate.domain.folder.api;

import barcode.phomate.domain.folder.application.FolderService;
import barcode.phomate.domain.folder.dto.FolderCreateRequestDTO;
import barcode.phomate.domain.folder.dto.FolderDetailResponseDTO;
import barcode.phomate.domain.folder.dto.FolderResponseDTO;
import barcode.phomate.domain.folder.dto.FolderUpdateRequestDTO;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/folders")
public class FolderController {

    private final FolderService folderService;

    @PostMapping
    @Operation(summary = "폴더 생성", description = "폴더 생성 API")
    public ResponseEntity<FolderResponseDTO> createFolder(
            @AuthenticationPrincipal Long memberId,
            @RequestBody FolderCreateRequestDTO request
            ) {
        FolderResponseDTO response = folderService.createFolder(memberId, request);
        return ResponseEntity
                .created(URI.create("/api/folders/" + response.folderId()))
                .body(response);
    }

    @GetMapping
    @Operation(summary = "폴더 목록 조회", description = "내가 만든 폴더 + 초대 수락한 공유 폴더 목록 조회 API")
    public ResponseEntity<List<FolderResponseDTO>> getFolderList(
            @AuthenticationPrincipal Long memberId
    ) {
        return ResponseEntity.ok(folderService.getFolderList(memberId));
    }

    @GetMapping("/{folderId}")
    @Operation(summary = "폴더 상세 조회", description = "폴더 상세 조회 API")
    public ResponseEntity<FolderDetailResponseDTO> getFolderDetail(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long folderId
    ) {
        return ResponseEntity.ok(folderService.getFolderDetail(memberId, folderId));
    }

    @PatchMapping("/{folderId}")
    @Operation(summary = "폴더 정보 수정", description = "폴더 이름 수정 API(소유자만 가능)")
    public ResponseEntity<Void> updateFolder(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long folderId,
            @RequestBody FolderUpdateRequestDTO requestDTO
            ) {
        folderService.updateFolder(memberId, folderId, requestDTO);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{folderId}")
    @Operation(summary = "폴더 삭제", description = "폴더 삭제 API(소유자만 가능)")
    public ResponseEntity<Void> deleteFolder(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long folderId
    ) {
        folderService.deleteFolder(memberId, folderId);
        return ResponseEntity.noContent().build();
    }
}
