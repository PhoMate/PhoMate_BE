package barcode.phomate.domain.chat.api;

import barcode.phomate.domain.chat.application.AgentService;
import barcode.phomate.domain.chat.application.ChatFolderService;
import barcode.phomate.domain.chat.application.ChatService;
import barcode.phomate.domain.chat.dto.AgentRequestDTO;
import barcode.phomate.domain.chat.dto.ChatFolderConfirmRequestDTO;
import barcode.phomate.domain.chat.dto.ChatFolderConfirmResponseDTO;
import barcode.phomate.domain.chat.dto.ChatFolderPreviewRequestDTO;
import barcode.phomate.domain.chat.dto.ChatFolderPreviewResponseDTO;
import barcode.phomate.domain.chat.dto.ChatSearchStreamRequestDTO;
import barcode.phomate.domain.chat.dto.ChatSendResponseDTO;
import barcode.phomate.domain.chat.dto.ChatStreamRequestDTO;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatFolderService chatFolderService;
    private final AgentService agentService;


    @PostMapping("/sessions/start")
    @Operation(summary = "채팅 세션 생성", description = "수정 챗봇 대화 세션(ChatSession)을 생성합니다.")
    public ResponseEntity<Long> startSession(
            @AuthenticationPrincipal Long memberId
    ) {
        return ResponseEntity.ok(chatService.startSession(memberId));
    }

    @PostMapping("/send-edit")
    @Operation(summary = "수정 챗봇 메시지 전송", description = "사용자 메시지를 저장하고, 편집을 수행한 뒤 editedUrl을 반환합니다.")
    public ResponseEntity<ChatSendResponseDTO> sendEdit(
            @AuthenticationPrincipal Long memberId,
            @RequestParam Long chatSessionId,
            @RequestParam Long editSessionId,
            @RequestParam String userText
    ) {
        return ResponseEntity.ok(
                chatService.sendEdit(memberId, chatSessionId, editSessionId, userText)
        );
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "텍스트 스트리밍", description = "SSE로 delta 반환")
    public Flux<ServerSentEvent<String>> streamText(@RequestBody ChatStreamRequestDTO request) {
        return chatService.streamText(request);
    }

    @PostMapping(value = "/search/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "검색 텍스트 스트리밍", description = "SSE로 results + delta 반환")
    public Flux<ServerSentEvent<String>> streamSearch(@AuthenticationPrincipal Long memberId,
                                                      @RequestBody ChatSearchStreamRequestDTO request) {
        return chatService.streamSearch(memberId, request);
    }

    @PostMapping("/folders/preview")
    @Operation(summary = "AI 자동 폴더 미리보기",
            description = "자연어로 키워드를 입력하면 유사한 사진 목록을 반환합니다.")
    public ResponseEntity<ChatFolderPreviewResponseDTO> folderPreview(
            @AuthenticationPrincipal Long memberId,
            @RequestBody ChatFolderPreviewRequestDTO request
    ) {
        log.info("[folderPreview] memberId={}", memberId);
        log.info("[folderPreview] request={}", request);

        return ResponseEntity.ok(chatFolderService.preview(memberId, request));
    }

    @PostMapping("/folders/confirm")
    @Operation(summary = "AI 자동 폴더 생성 확정",
            description = "수락 시 폴더를 생성합니다. 거절 시 folderId는 null.")
    public ResponseEntity<ChatFolderConfirmResponseDTO> folderConfirm(
            @AuthenticationPrincipal Long memberId,
            @RequestBody ChatFolderConfirmRequestDTO request
    ) {
        return ResponseEntity.ok(chatFolderService.confirm(memberId, request));
    }

    // 편집 / 검색 / 폴더 생성 다 여기로 들어옴
    @PostMapping(value = "/agent/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "AI Agent 통합 실행",
            description = "자연어 1문장으로 편집·검색·폴더 생성을 자동 처리합니다. SSE로 진행 상황 스트리밍.")
    public Flux<ServerSentEvent<String>> agentRun(
            @AuthenticationPrincipal Long memberId,
            @RequestBody AgentRequestDTO request
    ) {
        return agentService.run(memberId, request);
    }
}
