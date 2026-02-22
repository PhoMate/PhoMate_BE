package barcode.phomate.domain.folder.application;

import barcode.phomate.domain.folder.dto.FolderCreateRequestDTO;
import barcode.phomate.domain.folder.dto.FolderDetailResponseDTO;
import barcode.phomate.domain.folder.dto.FolderResponseDTO;
import barcode.phomate.domain.folder.dto.FolderUpdateRequestDTO;

import java.util.List;

public interface FolderService {

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

}
