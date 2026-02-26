package barcode.phomate.domain.folder.application;

import barcode.phomate.domain.folder.dto.FolderCreateRequestDTO;
import barcode.phomate.domain.folder.dto.FolderDetailResponseDTO;
import barcode.phomate.domain.folder.dto.FolderInvitationReplyRequestDTO;
import barcode.phomate.domain.folder.dto.FolderInvitationResponseDTO;
import barcode.phomate.domain.folder.dto.FolderInviteRequestDTO;
import barcode.phomate.domain.folder.dto.FolderMemberRoleResponseDTO;
import barcode.phomate.domain.folder.dto.FolderResponseDTO;
import barcode.phomate.domain.folder.dto.FolderRoleUpdateRequestDTO;
import barcode.phomate.domain.folder.dto.FolderUpdateRequestDTO;

import java.util.List;

public interface FolderService {

    // -------------1. 수동 폴더--------------
    // 폴더 생성
    FolderResponseDTO createFolder(Long memberId, FolderCreateRequestDTO request);

    // 폴더 목록 조회
    List<FolderResponseDTO> getFolderList(Long memberId);

    // 폴더 상세 조회
    FolderDetailResponseDTO getFolderDetail(Long memberId, Long folderId);

    // 폴더 정보(이름) 수정
    void updateFolder(Long memberId, Long folderId, FolderUpdateRequestDTO request);

    // 폴더 삭제
    void deleteFolder(Long memberId, Long folderId);

    // -------------2. 공유 폴더--------------
    // 공유 폴더 초대
    void inviteMember(Long requestMemberId, Long folderId, FolderInviteRequestDTO request);

    // 공유 폴더 초대 여부 조회
    FolderInvitationResponseDTO getInvitation(Long memberId, Long folderId);

    // 공유 폴더 초대 수락/거절
    void replyInvitation(Long memberId, Long folderId, FolderInvitationReplyRequestDTO request);

    // 공유 폴더 권한 조회
    FolderMemberRoleResponseDTO getMemberRole(Long requestMemberId, Long folderId, Long targetMemberId);

    // 공유 폴더 권한 부여/변경
    void updateMemberRole(Long requestMemberId, Long folderId, Long targetMemberId, FolderRoleUpdateRequestDTO request);
}
