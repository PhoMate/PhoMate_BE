package barcode.phomate.domain.chat.api;

import barcode.phomate.domain.chat.application.ChatService;
import barcode.phomate.domain.chat.dto.ChatSendResponseDTO;
import barcode.phomate.domain.chat.dto.ChatStreamRequestDTO;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/sessions/start")
    @Operation(summary = "채팅 세션 생성", description = "수정 챗봇 대화 세션(ChatSession)을 생성합니다.")
    public ResponseEntity<Long> startSession(
            @RequestParam Long memberId
    ) {
        return ResponseEntity.ok(chatService.startChatSession(memberId));
    }

    @PostMapping("/send-edit")
    @Operation(summary = "수정 챗봇 메시지 전송", description = "사용자 메시지를 저장하고, 편집을 수행한 뒤 editedUrl을 반환합니다.")
    public ResponseEntity<ChatSendResponseDTO> sendEdit(
            @RequestParam Long memberId,
            @RequestParam Long chatSessionId,
            @RequestParam Long editSessionId,
            @RequestParam String userText
    ) {
        return ResponseEntity.ok(
                chatService.sendEditMessage(memberId, chatSessionId, editSessionId, userText)
        );
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "텍스트 스트리밍", description = "SSE로 delta 반환")
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatStreamRequestDTO request) {
        return chatService.streamText(request);
    }
}
