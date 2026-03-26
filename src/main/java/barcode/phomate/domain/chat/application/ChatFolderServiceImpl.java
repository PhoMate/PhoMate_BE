package barcode.phomate.domain.chat.application;

import barcode.phomate.domain.chat.domain.entity.ChatSession;
import barcode.phomate.domain.chat.domain.repository.ChatSessionRepository;
import barcode.phomate.domain.chat.dto.ChatFolderConfirmRequestDTO;
import barcode.phomate.domain.chat.dto.ChatFolderConfirmResponseDTO;
import barcode.phomate.domain.chat.dto.ChatFolderPreviewRequestDTO;
import barcode.phomate.domain.chat.dto.ChatFolderPreviewResponseDTO;
import barcode.phomate.domain.folder.domain.entity.Folder;
import barcode.phomate.domain.folder.domain.entity.FolderType;
import barcode.phomate.domain.folder.domain.entity.PhotoFolder;
import barcode.phomate.domain.folder.domain.repository.FolderRepository;
import barcode.phomate.domain.folder.domain.repository.PhotoFolderRepository;
import barcode.phomate.domain.folder.dto.PhotoInfoDTO;
import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.domain.photo.domain.entity.Photo;
import barcode.phomate.domain.photo.domain.repository.PhotoRepository;
import barcode.phomate.global.exception.ForbiddenException;
import barcode.phomate.global.exception.NotFoundException;
import barcode.phomate.global.fastapi.client.SearchWorkerClient;
import barcode.phomate.global.fastapi.dto.SearchHitDTO;
import barcode.phomate.global.fastapi.dto.TextSearchRequestDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ChatFolderServiceImpl implements ChatFolderService {

    private static final int MAX_TOP_K = 50;
    private static final double SCORE_THRESHOLD = 0.02;

    private final WebClient openAiWebClient;
    private final SearchWorkerClient searchWorkerClient;
    private final ChatSessionRepository chatSessionRepository;
    private final MemberRepository memberRepository;
    private final PhotoRepository photoRepository;
    private final FolderRepository folderRepository;
    private final PhotoFolderRepository photoFolderRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.openai.model:gpt-4o-mini}")
    private String openAiModel;

    @Value("${app.cdn.base-url}")
    private String cloudFrontBaseUrl;

    // 1. 미리보기 : 자연어 -> GPT 키워드 추출 -> FastAPI 벡터 검색
    @Override
    public ChatFolderPreviewResponseDTO preview(Long memberId, ChatFolderPreviewRequestDTO request) {

        log.info("[preview] memberId={}", memberId);
        log.info("[preview] chatSessionId={}", request.chatSessionId());
        log.info("[preview] userText={}", request.userText());

        // 세션 검증
        ChatSession session = chatSessionRepository.findById(request.chatSessionId())
                .orElseThrow(() -> new NotFoundException("세션을 찾을 수 없습니다."));
        if(!session.getMember().getId().equals(memberId)) {
            throw new ForbiddenException("권한이 없습니다.");
        }

        // 1. GPT로 자연어 -> 키워드 추출
        String keyword = extractKeyword(session, request.userText());
        log.info("[AI-FOLDER PREVIEW] memberId={} userText={} keyword={}",
                memberId, request.userText(), keyword);

        // 2. FastAPI 벡터 검색 (내 사진만, 최대 50개)
        int topK = (request.topK() == null) ? 20 : Math.min(request.topK(), MAX_TOP_K);
        TextSearchRequestDTO searchReq = new TextSearchRequestDTO(
                keyword,
                topK,
                memberId,
                null,
                null
        );
        List<SearchHitDTO> hits = searchWorkerClient.searchText(searchReq).hits();

        log.info("[preview] raw hits size={}", hits.size());
        log.info("[preview] raw hits={}", hits);

        // 3. 공유폴더에 속한 photoId 목록 조회 (제외용)
        Set<Long> sharedPhotoIds = Set.copyOf(
                photoFolderRepository.findSharedFolderPhotoIdsByMemberId(memberId)
        );

        log.info("[preview] sharedPhotoIds={}", sharedPhotoIds);

        // 4. threshold 필터 + 공유폴더 사진 제외
        List<Long> filteredIds = hits.stream()
                .filter(hit -> hit.score() >= SCORE_THRESHOLD)
                .filter(hit -> !sharedPhotoIds.contains(hit.postId()))
                .map(SearchHitDTO::postId)
                .toList();

        log.info("[preview] filteredIds={}", filteredIds);

        // 5. photoId로 Photo 조회 -> PhotoInfoDTO 변환
        List<Photo> photos = photoRepository.findAllById(filteredIds);

        log.info("[preview] photos size={}", photos.size());
        log.info("[preview] photos={}", photos.stream().map(Photo::getId).toList());

        List<PhotoInfoDTO> photoInfos = photos.stream()
                .map(p -> new PhotoInfoDTO(
                        p.getId(),
                        cloudFrontBaseUrl + "/" + p.getPreviewKey(),
                        p.getShotAt()
                ))
                .toList();

        return new ChatFolderPreviewResponseDTO(keyword, photoInfos);

    }

    // 미리보기 수락 / 거절 : 수락 시 폴더 생성, 거절 시 null 반환
    @Override
    public ChatFolderConfirmResponseDTO confirm(Long memberId, ChatFolderConfirmRequestDTO request) {
        // 거절이면 아무것도 안 함
        if (!request.accepted()) {
            return new ChatFolderConfirmResponseDTO(null);
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new NotFoundException("회원을 찾을 수 없습니다."));

        // 폴더 생성 (AI가 만드는 PERSONAL 폴더)
        Folder folder = folderRepository.save(Folder.builder()
                .folderName(request.folderName())
                .type(FolderType.PERSONAL)
                .owner(member)
                .build());

        // PhotoFolder 매핑 저장
        List<Photo> photos = photoRepository.findAllById(request.photoIds());
        List<PhotoFolder> mappings = photos.stream()
                .map(photo -> PhotoFolder.builder()
                        .folder(folder)
                        .photo(photo)
                        .build())
                .toList();
        photoFolderRepository.saveAll(mappings);

        log.info("[AI-FOLDER CONFIRM] memberId={} folderId={} photoCount={}",
                memberId, folder.getId(), mappings.size());

        return new ChatFolderConfirmResponseDTO(folder.getId());
    }

    // GPT로 자연어 -> 키워드 추출하는 메서드
    private String extractKeyword(ChatSession session, String userText) {
        try{
            String response = openAiWebClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "model", openAiModel,
                            "stream", false,
                            "messages", List.of(
                                    Map.of("role", "system", "content", buildKeywordExtractPrompt()),
                                    Map.of("role", "user", "content",
                                            buildKeywordExtractInput(session.getCurrentSearchQuery(), userText))
                            )
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            String content = extractAssistantContent(response);
            String keyword = parseKeywordJson(content);

            // 세션에 최신 검색 키워드 저장
            session.updateSearchQuery(keyword);
            chatSessionRepository.save(session);

            return keyword;

        } catch (Exception e) {
            log.error("[AI-FOLDER KEYWORD EXTRACT FAIL] userText={}", userText, e);
            // GPT 실패 시, userText 앞 10자 fallback
            return userText.length() > 10 ? userText.substring(0, 10) : userText;
        }
        }

    // GPT 시스템 프롬프트 생성
    private String buildKeywordExtractPrompt() {
        return """
                너는 사진 폴더 검색 서비스의 키워드 추출기다.
                
                역할:
                - 사용자의 자연어 메시지에서 폴더로 만들 사진 검색 키워드를 하나만 추출해라.
                - 이전 검색 상태가 있으면 참고해라.
                
                출력은 반드시 JSON만:
                {"keyword":"..."}
                
                규칙:
                - keyword는 벡터 검색에 적합한 핵심 단어 하나
                - 설명, 문장, 다른 말은 절대 출력하지 마라
                예시: "느좋카페 폴더 만들어줘" → {"keyword":"느좋카페"}
                예시: "바다에서 찍은 사진 모아줘" → {"keyword":"바다"}
                """;
    }

    // GPT의 문맥 유지를 위해서
    private String buildKeywordExtractInput(String currentQuery, String userText) {
        if (currentQuery == null || currentQuery.isBlank()) {
            return "[사용자 요청]\n" + userText;
        }
        return "[이전 검색 상태]\n" + currentQuery + "\n\n[사용자 요청]\n" + userText;
    }

    // GPT 응답에서 content 추출
    private String extractAssistantContent(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    // 키워드 값 파싱
    private String parseKeywordJson(String content) {
        try {
            JsonNode node = mapper.readTree(content);
            String kw = node.path("keyword").asText("").trim();
            return kw.isBlank() ? content.trim() : kw;
        } catch (Exception e) {
            return content == null ? "" : content.trim();
        }
    }
}
