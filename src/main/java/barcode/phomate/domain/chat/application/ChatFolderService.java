package barcode.phomate.domain.chat.application;

import barcode.phomate.domain.chat.dto.ChatFolderConfirmRequestDTO;
import barcode.phomate.domain.chat.dto.ChatFolderConfirmResponseDTO;
import barcode.phomate.domain.chat.dto.ChatFolderPreviewRequestDTO;
import barcode.phomate.domain.chat.dto.ChatFolderPreviewResponseDTO;

public interface ChatFolderService {

    // AI 자동폴더 제안(미리보기)
    ChatFolderPreviewResponseDTO preview(Long memberId, ChatFolderPreviewRequestDTO request);

    // AI 자동 폴더 제안(미리보기) 수락 시 폴더 생성, 거절 시 null 반환
    ChatFolderConfirmResponseDTO confirm(Long memberId, ChatFolderConfirmRequestDTO request);
}
