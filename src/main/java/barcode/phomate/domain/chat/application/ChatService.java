package barcode.phomate.domain.chat.application;

import barcode.phomate.domain.chat.dto.ChatSendResponseDTO;
import barcode.phomate.domain.chat.dto.ChatStreamRequestDTO;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

// 채팅 세션 생성 / 유지용
public interface ChatService {

    Long startSession(Long memberId);

    ChatSendResponseDTO sendEdit(Long memberId, Long chatSessionId, Long editSessionId, String userText);

    // 텍스트 스트리밍
    Flux<ServerSentEvent<String>> streamText(ChatStreamRequestDTO request);
}
