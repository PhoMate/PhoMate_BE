package barcode.phomate.domain.chat.application;

import barcode.phomate.domain.chat.domain.entity.ChatMessage;
import barcode.phomate.domain.chat.domain.entity.ChatSession;
import barcode.phomate.domain.chat.domain.repository.ChatMessageRepository;
import barcode.phomate.domain.chat.domain.repository.ChatSessionRepository;
import barcode.phomate.domain.chat.dto.ChatSendResponseDTO;
import barcode.phomate.domain.chat.dto.ChatStreamRequestDTO;
import barcode.phomate.domain.edit.application.EditService;
import barcode.phomate.domain.edit.domain.entity.EditVersion;
import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.global.exception.NotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
@Transactional
public class ChatServiceImpl implements ChatService{

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MemberRepository memberRepository;
    private final EditService editService;

    @Value("${app.cdn.base-url}")
    private String cloudFrontBaseUrl;

    // 1. 채팅 세션 생성
    @Override
    public Long startSession(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new NotFoundException("Member를 찾을 수 없습니다."));

        ChatSession session = chatSessionRepository.save(ChatSession.builder()
                .member(member)
                .build());

        return session.getId();
    }

    // 2. 편집 챗봇 메시지 전송
    @Override
    public ChatSendResponseDTO sendEdit(Long memberId, Long chatSessionId, Long editSessionId, String userText) {
        ChatSession chatSession = chatSessionRepository.findById(chatSessionId)
                .orElseThrow(() -> new NotFoundException("ChatSession을 찾을 수 없습니다."));

        ChatMessage userMsg = chatMessageRepository.save(ChatMessage.builder()
                .chatSession(chatSession)
                .parent(null)
                .content(userText)
                .build());

        // userText 그대로 나노바나나 편집 프롬프트로 사용
        EditVersion newVersion = editService.chatEdit(memberId, editSessionId, userText);

        // 프론트 표시용 URL (CloudFront base-url + S3 key)
        String editedUrl = cloudFrontBaseUrl + "/" + newVersion.getS3Key();

        String assistantText =
                "요청을 반영하여 새 버전을 만들었습니다.\n" +
                        "결과가 마음에 드시나요?";

        ChatMessage assistantMsg = chatMessageRepository.save(ChatMessage.builder()
                .chatSession(chatSession)
                .parent(userMsg) // userMsg에 대한 응답(undo / redo 시 사용)
                .content(assistantText)
                .build());

        return ChatSendResponseDTO.of(
                chatSessionId,
                userMsg.getId(),
                assistantMsg.getId(),
                assistantText,
                editedUrl
        );
    }

    @Override
    public Flux<ServerSentEvent<String>> streamText(ChatStreamRequestDTO request) {
        return null;
    }
}
