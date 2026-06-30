package barcode.phomate.domain.chat.application;

import barcode.phomate.domain.chat.domain.entity.ChatMessage;
import barcode.phomate.domain.chat.domain.entity.ChatSession;
import barcode.phomate.domain.chat.domain.repository.ChatMessageRepository;
import barcode.phomate.domain.chat.domain.repository.ChatSessionRepository;
import barcode.phomate.domain.chat.dto.AgentRequestDTO;
import barcode.phomate.domain.chat.dto.PhotoSearchItemDTO;
import barcode.phomate.domain.edit.application.EditService;
import barcode.phomate.domain.edit.domain.entity.EditVersion;
import barcode.phomate.domain.folder.domain.entity.Folder;
import barcode.phomate.domain.folder.domain.entity.FolderType;
import barcode.phomate.domain.folder.domain.entity.PhotoFolder;
import barcode.phomate.domain.folder.domain.repository.FolderRepository;
import barcode.phomate.domain.folder.domain.repository.PhotoFolderRepository;
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
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentServiceImpl implements AgentService {

    private static final int SEARCH_TOP_K = 50;
    private static final double SCORE_THRESHOLD = 0.02;
    // Gemini 실패 시 재시도 횟수
    private static final int EDIT_MAX_RETRY = 2;

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MemberRepository memberRepository;
    private final PhotoRepository photoRepository;
    private final FolderRepository folderRepository;
    private final PhotoFolderRepository photoFolderRepository;
    private final EditService editService;
    private final SearchWorkerClient searchWorkerClient;
    private final WebClient openAiWebClient;
    private final PendingFolderCache pendingFolderCache;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.openai.model:gpt-4o-mini}")
    private String openAiModel;

    @Value("${app.cdn.base-url}")
    private String cloudFrontBaseUrl;

    // 편집 / 검색 / 폴더 생성 통합 진입점
    @Override
    public Flux<ServerSentEvent<String>> run(Long memberId, AgentRequestDTO request) {

        return Mono.fromCallable(() -> {

                    ChatSession session = chatSessionRepository.findById(request.getChatSessionId())
                            .orElseThrow(() -> new NotFoundException("세션을 찾을 수 없습니다."));

                    if (!session.getMember().getId().equals(memberId)) {
                        throw new ForbiddenException("권한이 없습니다.");
                    }

                    // user 메시지 저장
                    ChatMessage userMsg = chatMessageRepository.save(ChatMessage.builder()
                            .chatSession(session)
                            .parent(null)
                            .content(request.getUserText())
                            .build());

                    return new SessionCtx(session, userMsg);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ctx -> {

                    return classifyIntent(request.getUserText(), pendingFolderCache.get(request.getChatSessionId()) != null)
                            .flatMapMany(intent -> {
                                log.info("[AGENT] intent={} userText={}", intent, request.getUserText());

                                return switch (intent) {
                                    case "edit" -> handleEdit(memberId, request, ctx);
                                    case "search" -> handleSearch(memberId, request, ctx);
                                    case "folder" -> handleFolder(memberId, request, ctx);
                                    case "search_and_folder" -> handleSearchAndFolder(memberId, request, ctx);
                                    case "confirm" -> handleConfirm(memberId, request, ctx);
                                    case "reject" -> handleReject(request, ctx);
                                    default -> handleSearch(memberId, request, ctx);
                                };
                            })
                            .onErrorResume(e -> {
                                log.error("[AGENT ERROR] sessionId={}", ctx.session().getId(), e);
                                return Flux.just(
                                        ServerSentEvent.<String>builder().event("error").data("agent_failed").build(),
                                        ServerSentEvent.<String>builder().event("done").data("ok").build()
                                );
                            });
                });
    }

    private Mono<String> classifyIntent(String userText, boolean hasPendingFolder) {
        return openAiWebClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", openAiModel,
                        "stream", false,
                        "messages", List.of(
                                Map.of("role", "system", "content", buildIntentClassifierPrompt(hasPendingFolder)),
                                Map.of("role", "user", "content", userText)
                        )
                ))
                .retrieve()
                .bodyToMono(String.class)
                .map(this::extractAssistantContent)
                .map(content -> {
                    try {
                        JsonNode node = mapper.readTree(content);
                        return node.path("intent").asText("search");
                    } catch (Exception e) {
                        return "search";
                    }
                })
                .onErrorReturn("search");
    }

    // 편집 처리 (멀티스텝 + 실패 시 재시도)
    private Flux<ServerSentEvent<String>> handleEdit(Long memberId, AgentRequestDTO request, SessionCtx ctx) {

        if (request.getEditSessionId() == null) {
            return Flux.just(
                    ServerSentEvent.<String>builder().event("delta").data("편집할 사진을 먼저 선택해 주세요!").build(),
                    ServerSentEvent.<String>builder().event("done").data("ok").build()
            );
        }

        return Mono.fromCallable(() -> {
                    // 멀티스텝 편집: GPT로 편집 단계 쪼개기
                    List<String> steps = splitEditSteps(request.getUserText());
                    log.info("[AGENT EDIT] steps={}", steps);

                    StringBuilder resultUrl = new StringBuilder();

                    for (String step : steps) {
                        // 단계별 편집 + 실패 시 재시도
                        EditVersion version = editWithRetry(memberId, request.getEditSessionId(), step);
                        resultUrl = new StringBuilder(cloudFrontBaseUrl + "/" + version.getS3Key());
                        log.info("[AGENT EDIT STEP] step={} versionIndex={}", step, version.getVersionIndex());
                    }

                    return resultUrl.toString();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(editedUrl -> {

                    String assistantText = "편집 완료했어요! 결과가 마음에 드시나요?";
                    saveAssistantMessage(ctx, assistantText);

                    return Flux.just(
                            ServerSentEvent.<String>builder().event("edited_url").data(editedUrl).build(),
                            ServerSentEvent.<String>builder().event("delta").data(assistantText).build(),
                            ServerSentEvent.<String>builder().event("done").data("ok").build()
                    );
                })
                .onErrorResume(e -> {
                    log.error("[AGENT EDIT ERROR]", e);
                    String msg = "편집 중 문제가 생겼어요. 다시 한 번 말씀해 주시겠어요?";
                    saveAssistantMessage(ctx, msg);
                    return Flux.just(
                            ServerSentEvent.<String>builder().event("delta").data(msg).build(),
                            ServerSentEvent.<String>builder().event("done").data("ok").build()
                    );
                });
    }

    // 검색 처리 (기존 streamSearch와 동일 흐름)
    private Flux<ServerSentEvent<String>> handleSearch(Long memberId, AgentRequestDTO request, SessionCtx ctx) {

        return Mono.fromCallable(() -> {

                    String query = buildSearchQuery(request.getUserText());
                    log.info("[AGENT SEARCH] query={}", query);

                    // 세션에 검색 쿼리 저장 (검색→폴더 이어질 때 사용)
                    ctx.session().updateSearchQuery(query);
                    chatSessionRepository.save(ctx.session());

                    TextSearchRequestDTO reqDto = new TextSearchRequestDTO(query, SEARCH_TOP_K, memberId, null, null);
                    List<Long> photoIds = searchWorkerClient.searchText(reqDto).hits().stream()
                            .filter(h -> h.score() >= SCORE_THRESHOLD)
                            .map(SearchHitDTO::postId)
                            .toList();

                    return toPhotoItems(photoIds);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(items -> {

                    String payloadJson = toJsonSafely(Map.of("items", items));
                    String assistantText = "원하는 사진이 보이시나요? 사진 속 상황을 조금 더 구체적으로 말씀해 주시면 더 잘 찾을 수 있어요.";
                    saveAssistantMessage(ctx, assistantText);

                    return Flux.just(
                            ServerSentEvent.<String>builder().event("results").data(payloadJson).build(),
                            ServerSentEvent.<String>builder().event("delta").data(assistantText).build(),
                            ServerSentEvent.<String>builder().event("done").data("ok").build()
                    );
                });
    }

    private Flux<ServerSentEvent<String>> handleFolder(Long memberId, AgentRequestDTO request, SessionCtx ctx) {

        return Mono.fromCallable(() -> {

                    String keyword = buildFolderKeyword(request.getUserText());
                    log.info("[AGENT FOLDER] keyword={}", keyword);

                    TextSearchRequestDTO reqDto = new TextSearchRequestDTO(keyword, SEARCH_TOP_K, memberId, null, null);
                    List<Long> photoIds = searchWorkerClient.searchText(reqDto).hits().stream()
                            .filter(h -> h.score() >= SCORE_THRESHOLD)
                            .map(SearchHitDTO::postId)
                            .toList();

                    Set<Long> sharedIds = Set.copyOf(photoFolderRepository.findSharedFolderPhotoIdsByMemberId(memberId));
                    List<Long> filtered = photoIds.stream().filter(id -> !sharedIds.contains(id)).toList();

                    List<PhotoSearchItemDTO> items = toPhotoItems(filtered);

                    pendingFolderCache.put(request.getChatSessionId(), keyword, filtered);

                    return new FolderPreviewResult(keyword, items);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(result -> {

                    String assistantText = String.format(
                            "'%s' 관련 사진 %d장을 찾았어요. 이 사진들로 폴더를 만들까요?",
                            result.keyword(), result.items().size()
                    );
                    saveAssistantMessage(ctx, assistantText);

                    String payloadJson = toJsonSafely(Map.of("items", result.items()));

                    return Flux.just(
                            ServerSentEvent.<String>builder().event("results").data(payloadJson).build(),
                            ServerSentEvent.<String>builder().event("delta").data(assistantText).build(),
                            ServerSentEvent.<String>builder().event("done").data("ok").build()
                    );
                });
    }

    private Flux<ServerSentEvent<String>> handleConfirm(Long memberId, AgentRequestDTO request, SessionCtx ctx) {

        PendingFolderCache.PendingFolder pending = pendingFolderCache.get(request.getChatSessionId());

        if (pending == null) {
            String msg = "만들 폴더가 없어요. 먼저 어떤 사진을 모을지 말씀해 주세요!";
            saveAssistantMessage(ctx, msg);
            return Flux.just(
                    ServerSentEvent.<String>builder().event("delta").data(msg).build(),
                    ServerSentEvent.<String>builder().event("done").data("ok").build()
            );
        }

        return Mono.fromCallable(() -> {

                    Member member = memberRepository.findById(memberId)
                            .orElseThrow(() -> new NotFoundException("회원을 찾을 수 없습니다."));

                    Folder folder = folderRepository.save(Folder.builder()
                            .folderName(pending.folderName())
                            .type(FolderType.PERSONAL)
                            .owner(member)
                            .build());

                    List<Photo> photos = photoRepository.findAllById(pending.photoIds());
                    List<PhotoFolder> mappings = photos.stream()
                            .map(p -> PhotoFolder.builder().folder(folder).photo(p).build())
                            .toList();
                    photoFolderRepository.saveAll(mappings);

                    pendingFolderCache.clear(request.getChatSessionId());

                    log.info("[AGENT FOLDER CONFIRMED] folderId={} photoCount={}", folder.getId(), mappings.size());

                    return new FolderResult(folder.getId(), pending.folderName(), mappings.size());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(result -> {

                    String assistantText = String.format(
                            "'%s' 폴더를 만들었어요! 사진 %d장이 담겼습니다.",
                            result.folderName(), result.photoCount()
                    );
                    saveAssistantMessage(ctx, assistantText);

                    String payloadJson = toJsonSafely(Map.of("folderId", result.folderId()));

                    return Flux.just(
                            ServerSentEvent.<String>builder().event("folder_created").data(payloadJson).build(),
                            ServerSentEvent.<String>builder().event("delta").data(assistantText).build(),
                            ServerSentEvent.<String>builder().event("done").data("ok").build()
                    );
                });
    }

    private Flux<ServerSentEvent<String>> handleReject(AgentRequestDTO request, SessionCtx ctx) {

        pendingFolderCache.clear(request.getChatSessionId());

        String msg = "알겠어요, 폴더를 만들지 않을게요. 다른 사진을 찾아드릴까요?";
        saveAssistantMessage(ctx, msg);

        return Flux.just(
                ServerSentEvent.<String>builder().event("delta").data(msg).build(),
                ServerSentEvent.<String>builder().event("done").data("ok").build()
        );
    }

    private Flux<ServerSentEvent<String>> handleSearchAndFolder(Long memberId, AgentRequestDTO request, SessionCtx ctx) {

        return Mono.fromCallable(() -> {

                    String keyword = buildFolderKeyword(request.getUserText());
                    log.info("[AGENT SEARCH_AND_FOLDER] keyword={}", keyword);

                    TextSearchRequestDTO reqDto = new TextSearchRequestDTO(keyword, SEARCH_TOP_K, memberId, null, null);
                    List<Long> photoIds = searchWorkerClient.searchText(reqDto).hits().stream()
                            .filter(h -> h.score() >= SCORE_THRESHOLD)
                            .map(SearchHitDTO::postId)
                            .toList();

                    Set<Long> sharedIds = Set.copyOf(photoFolderRepository.findSharedFolderPhotoIdsByMemberId(memberId));
                    List<Long> filtered = photoIds.stream().filter(id -> !sharedIds.contains(id)).toList();

                    List<PhotoSearchItemDTO> items = toPhotoItems(filtered);

                    pendingFolderCache.put(request.getChatSessionId(), keyword, filtered);

                    return new FolderPreviewResult(keyword, items);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(result -> {

                    String assistantText = String.format(
                            "'%s' 관련 사진 %d장을 찾았어요. 이 사진들로 폴더를 만들까요?",
                            result.keyword(), result.items().size()
                    );
                    saveAssistantMessage(ctx, assistantText);

                    String resultsJson = toJsonSafely(Map.of("items", result.items()));

                    return Flux.just(
                            ServerSentEvent.<String>builder().event("results").data(resultsJson).build(),
                            ServerSentEvent.<String>builder().event("delta").data(assistantText).build(),
                            ServerSentEvent.<String>builder().event("done").data("ok").build()
                    );
                });
    }

    // 편집 실패 시 최대 EDIT_MAX_RETRY번 재시도
    private EditVersion editWithRetry(Long memberId, Long editSessionId, String prompt) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= EDIT_MAX_RETRY; attempt++) {
            try {
                return editService.chatEdit(memberId, editSessionId, prompt);
            } catch (Exception e) {
                lastException = e;
                log.warn("[AGENT EDIT RETRY] attempt={}/{} prompt={} error={}", attempt, EDIT_MAX_RETRY, prompt, e.getMessage());
            }
        }

        throw new RuntimeException("편집 실패: " + prompt, lastException);
    }

    // GPT로 편집 명령을 단계별로 쪼개기
    // ex) "밝게 하고 배경 흐리게" -> ["밝기 보정", "배경 블러"]
    private List<String> splitEditSteps(String userText) {
        try {
            String response = openAiWebClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "model", openAiModel,
                            "stream", false,
                            "messages", List.of(
                                    Map.of("role", "system", "content", buildEditStepSplitterPrompt()),
                                    Map.of("role", "user", "content", userText)
                            )
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            String content = extractAssistantContent(response);
            JsonNode node = mapper.readTree(content);
            JsonNode stepsNode = node.path("steps");

            if (stepsNode.isArray() && !stepsNode.isEmpty()) {
                List<String> steps = new java.util.ArrayList<>();
                stepsNode.forEach(s -> steps.add(s.asText()));
                return steps;
            }
        } catch (Exception e) {
            log.warn("[AGENT EDIT SPLIT FAIL] fallback to single step. userText={}", userText);
        }

        // GPT 실패하면 그냥 통째로 1단계
        return List.of(userText);
    }

    // 검색용 영어 쿼리 생성 (기존 ChatServiceImpl의 buildQueryPlannerPrompt와 동일 흐름)
    private String buildSearchQuery(String userText) {
        try {
            String response = openAiWebClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "model", openAiModel,
                            "stream", false,
                            "messages", List.of(
                                    Map.of("role", "system", "content", buildQueryPlannerPrompt()),
                                    Map.of("role", "user", "content", "User request:\n" + userText)
                            )
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            String content = extractAssistantContent(response);
            JsonNode node = mapper.readTree(content);
            String query = node.path("query").asText("").trim();
            return query.isBlank() ? userText : query;
        } catch (Exception e) {
            log.warn("[AGENT SEARCH QUERY FAIL] fallback to userText");
            return userText;
        }
    }

    // 폴더 키워드 추출 (기존 ChatFolderServiceImpl의 extractKeyword와 동일 흐름)
    private String buildFolderKeyword(String userText) {
        try {
            String response = openAiWebClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "model", openAiModel,
                            "stream", false,
                            "messages", List.of(
                                    Map.of("role", "system", "content", buildKeywordExtractPrompt()),
                                    Map.of("role", "user", "content", "[사용자 요청]\n" + userText)
                            )
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            String content = extractAssistantContent(response);
            JsonNode node = mapper.readTree(content);
            String keyword = node.path("keyword").asText("").trim();
            return keyword.isBlank() ? userText : keyword;
        } catch (Exception e) {
            log.warn("[AGENT FOLDER KEYWORD FAIL] fallback to userText");
            return userText;
        }
    }

    // photoId 리스트 -> PhotoSearchItemDTO 변환
    private List<PhotoSearchItemDTO> toPhotoItems(List<Long> photoIds) {
        if (photoIds == null || photoIds.isEmpty()) return List.of();

        List<Photo> photos = photoRepository.findByIdIn(photoIds);
        Map<Long, Photo> byId = photos.stream().collect(Collectors.toMap(Photo::getId, p -> p));

        return photoIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(p -> p.getPreviewKey() != null && !p.getPreviewKey().isBlank())
                .filter(p -> p.getThumbnailKey() != null && !p.getThumbnailKey().isBlank())
                .filter(p -> !"TEMP".equals(p.getPreviewKey()))
                .filter(p -> !"TEMP".equals(p.getThumbnailKey()))
                .filter(p -> p.getDeletedAt() == null)
                .map(p -> new PhotoSearchItemDTO(
                        p.getId(),
                        cloudFrontBaseUrl + "/" + p.getThumbnailKey(),
                        cloudFrontBaseUrl + "/" + p.getPreviewKey(),
                        p.getShotAt() == null ? null : p.getShotAt().toString(),
                        p.getDescription()
                ))
                .toList();
    }

    // assistant 메시지 DB 저장
    private void saveAssistantMessage(SessionCtx ctx, String content) {
        Schedulers.boundedElastic().schedule(() -> {
            try {
                chatMessageRepository.save(ChatMessage.builder()
                        .chatSession(ctx.session())
                        .parent(ctx.userMsg())
                        .content(content)
                        .build());
            } catch (Exception e) {
                log.error("[AGENT ASSIST SAVE FAIL] sessionId={}", ctx.session().getId(), e);
            }
        });
    }

    private String buildIntentClassifierPrompt(boolean hasPendingFolder) {
        String pendingNote = hasPendingFolder
                ? "\nNote: there is a pending folder creation waiting for the user's confirmation. If the user says yes/agree/confirm, classify as \"confirm\". If they say no/cancel/different, classify as \"reject\".\n"
                : "";

        return """
You are an intent classifier for a photo management app.

Classify the user's request into one of:
- "edit"              : wants to edit/modify a specific photo
- "search"            : wants to find/look for photos
- "folder"            : wants to create a folder from photos
- "search_and_folder" : wants to find photos AND create a folder at once
- "confirm"           : agrees to a pending folder creation suggestion
- "reject"            : declines a pending folder creation suggestion
"""
                + pendingNote + """

Output format (JSON only):
{"intent":"..."}

Examples:
"이 사진 밝게 해줘" -> {"intent":"edit"}
"배경 흐리게 하고 색감도 따뜻하게 바꿔줘" -> {"intent":"edit"}
"바다 사진 찾아줘" -> {"intent":"search"}
"느좋카페 사진 폴더로 만들어줘" -> {"intent":"folder"}
"여행 사진 찾아서 폴더 만들어줘" -> {"intent":"search_and_folder"}
"응 좋아" -> {"intent":"confirm"}
"네 만들어줘" -> {"intent":"confirm"}
"아니 됐어" -> {"intent":"reject"}
""";
    }

    // 편집 단계 분리 프롬프트
    private String buildEditStepSplitterPrompt() {
        return """
You split a photo editing request into sequential steps.

Output format (JSON only):
{"steps":["step1","step2",...]}

Rules:
- Each step is one simple edit action.
- If only one action, return one step.
- Write each step as a short English instruction.

Examples:
"밝게 하고 배경 흐리게" -> {"steps":["increase brightness","blur background"]}
"색감 따뜻하게" -> {"steps":["make colors warmer"]}
"밝게 보정하고 색감도 따뜻하게 하고 배경도 흐리게 해줘" -> {"steps":["increase brightness","make colors warmer","blur background"]}
""";
    }

    // 검색 쿼리 생성 프롬프트 (기존 ChatServiceImpl과 동일)
    private String buildQueryPlannerPrompt() {
        return """
You create one short English description for photo search.

Output format:
{"query":"..."}

Rules:
- Return JSON only.
- Write in English.
- Keep it short and clear.
- Focus on what can be seen in a photo.
- Do not use search-style phrases like "find", "show me", "search for".
""";
    }

    // 폴더 키워드 추출 프롬프트 (기존 ChatFolderServiceImpl과 동일)
    private String buildKeywordExtractPrompt() {
        return """
너는 사진 폴더 검색 서비스의 키워드 추출기다.

역할:
- 사용자의 자연어 메시지에서 폴더로 만들 사진 검색 키워드를 하나만 추출해라.

출력은 반드시 JSON만:
{"keyword":"..."}

규칙:
- keyword는 벡터 검색에 적합한 핵심 단어 하나
- 설명, 문장, 다른 말은 절대 출력하지 마라
예시: "느좋카페 폴더 만들어줘" -> {"keyword":"느좋카페"}
예시: "바다에서 찍은 사진 모아줘" -> {"keyword":"바다"}
""";
    }

    private String extractAssistantContent(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String toJsonSafely(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private record SessionCtx(ChatSession session, ChatMessage userMsg) {}

    private record FolderResult(Long folderId, String folderName, int photoCount) {}

    private record FolderPreviewResult(String keyword, List<PhotoSearchItemDTO> items) {}
}
