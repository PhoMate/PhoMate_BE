package barcode.phomate.domain.folder.api;

import barcode.phomate.domain.folder.application.FolderService;
import barcode.phomate.domain.folder.dto.FolderCreateRequestDTO;
import barcode.phomate.domain.folder.dto.FolderDetailResponseDTO;
import barcode.phomate.domain.folder.dto.FolderInvitationReplyRequestDTO;
import barcode.phomate.domain.folder.dto.FolderInvitationResponseDTO;
import barcode.phomate.domain.folder.dto.FolderInviteRequestDTO;
import barcode.phomate.domain.folder.dto.FolderMemberRoleResponseDTO;
import barcode.phomate.domain.folder.dto.FolderResponseDTO;
import barcode.phomate.domain.folder.dto.FolderRoleUpdateRequestDTO;
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

    // 수동 폴더
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
            @RequestBody FolderUpdateRequestDTO request
            ) {
        folderService.updateFolder(memberId, folderId, request);
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

    // 공유 폴더
    @PostMapping("/{folderId}/members")
    @Operation(summary = "공유 폴더 초대", description = "ADMIN이 멤버를 공유 폴더에 초대하는 API")
    public ResponseEntity<Void> inviteMember(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long folderId,
            @RequestBody FolderInviteRequestDTO request
    ) {
        folderService.inviteMember(memberId, folderId, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{folderId}/members/invitation")
    @Operation(summary = "공유 폴더 초대 여부 조회", description = "내가 특정 폴더에 받은 대기 중인 초대 조회 API")
    public ResponseEntity<FolderInvitationResponseDTO> getInvitation(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long folderId
    ) {
        return ResponseEntity.ok(folderService.getInvitation(memberId, folderId));
    }

    @PatchMapping("/{folderId}/members/invitation")
    @Operation(summary = "공유 폴더 초대 수락/거절", description = "초대받은 멤버가 수락 또는 거절하는 API")
    public ResponseEntity<Void> replyInvitation(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long folderId,
            @RequestBody FolderInvitationReplyRequestDTO request
            ) {
        folderService.replyInvitation(memberId, folderId, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{folderId}/members/{targetMemberId}/role")
    @Operation(summary = "공유 폴더 권한 조회", description = "특정 멤버의 권한을 조회하는 API")
    public ResponseEntity<FolderMemberRoleResponseDTO> getMemberRole(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long folderId,
            @PathVariable Long targetMemberId
    ) {
        return ResponseEntity.ok(folderService.getMemberRole(memberId, folderId, targetMemberId));
    }

    @PatchMapping("/{folderId}/members/{targetMemberId}/role")
    @Operation(summary = "공유 폴더 권한 부여/변경", description = "ADMIN이 멤버의 권한을 변경하는 API")
    public ResponseEntity<Void> updateMemberRole(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long folderId,
            @PathVariable Long targetMemberId,
            @RequestBody FolderRoleUpdateRequestDTO request
    ) {
        folderService.updateMemberRole(memberId, folderId, targetMemberId, request);
        return ResponseEntity.noContent().build();
    }
}
