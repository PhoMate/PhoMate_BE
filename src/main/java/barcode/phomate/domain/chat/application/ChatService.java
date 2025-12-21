package barcode.phomate.domain.chat.application;

import barcode.phomate.domain.chat.dto.ChatSendResponseDTO;

// 채팅 세션 생성 / 유지용
public interface ChatService {

    Long startChatSession(Long memberId);

    ChatSendResponseDTO sendEditMessage(Long memberId, Long chatSessionId, Long editSessionId, String userText);
}
