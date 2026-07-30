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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentServiceImpl implements AgentService {

    private static final int SEARCH_TOP_K = 50;              // 검색(무한스크롤)용 top-K
    private static final int FOLDER_CANDIDATE_K = 200;       // 폴더용 후보 개수 (적응형 컷이 잘리지 않도록 넉넉히)
    private static final double FOLDER_CUT_RATIO = 0.75;     // 폴더: 최고 점수의 75% 이상만 담음 (쿼리별 적응형 상대 컷)
    private static final double FOLDER_MIN_TOP = 0.08;       // 폴더: 최고 점수가 이 미만이면 관련 사진 없음으로 간주
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");  // 날짜 계산은 항상 KST 기준
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
    private final ActiveEditSessionCache activeEditSessionCache;
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

                    // user 메시지 저장 (원문 그대로 — 히스토리 표시용)
                    ChatMessage userMsg = chatMessageRepository.save(ChatMessage.builder()
                            .chatSession(session)
                            .parent(null)
                            .content(request.getUserText())
                            .build());

                    // 문맥 해소: 지시어 치환 or 되물음 결정
                    ContextOutcome outcome = contextualize(session, userMsg.getId(), request.getUserText());

                    return new SessionCtx(session, userMsg, outcome.resolved(), outcome.clarify());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ctx -> {

                    // 문맥에서 대상을 특정하지 못하면 실행하지 않고 사용자에게 되물음
                    if (ctx.clarifyQuestion() != null) {
                        saveAssistantMessage(ctx, ctx.clarifyQuestion());
                        return Flux.just(
                                ServerSentEvent.<String>builder().event("delta").data(ctx.clarifyQuestion()).build(),
                                ServerSentEvent.<String>builder().event("done").data("ok").build()
                        );
                    }

                    return classifyIntent(ctx, pendingFolderCache.get(request.getChatSessionId()) != null)
                            .flatMapMany(intent -> {
                                log.info("[AGENT] intent={} resolved={}", intent, ctx.resolvedText());

                                // 편집이 아닌 동작으로 넘어가면 활성 편집 세션 바인딩 해제
                                // (이후 같은 사진을 다시 편집하면 예전 세션을 재개하지 않고 새로 시작)
                                if (!"edit".equals(intent)) {
                                    activeEditSessionCache.clear(request.getChatSessionId());
                                }

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

    // 최근 대화 N턴을 LLM messages 형식으로 구성 (system → 이전 대화 → 현재 메시지)
    // ChatMessage에는 role 필드가 없어 parent==null이면 user, 아니면 assistant로 판별한다.
    private List<Map<String, String>> withRecentHistory(String systemPrompt, ChatSession session,
                                                        Long excludeMsgId, String currentUserContent) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        List<ChatMessage> recent = chatMessageRepository.findTop10ByChatSessionOrderByCreatedAtDesc(session);
        // 최신순 → 오래된순으로 뒤집고, 방금 저장한 현재 메시지는 제외
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatMessage m = recent.get(i);
            if (excludeMsgId != null && m.getId().equals(excludeMsgId)) continue;
            String role = (m.getParent() == null) ? "user" : "assistant";
            messages.add(Map.of("role", role, "content", m.getContent()));
        }

        messages.add(Map.of("role", "user", "content", currentUserContent));
        return messages;
    }

    private record ContextOutcome(String resolved, String clarify) {}

    // 대화 맥락에 의존하는 지시어/참조 표현 (이게 있을 때만 문맥 해소 LLM을 태운다)
    private static final java.util.regex.Pattern CONTEXT_REF = java.util.regex.Pattern.compile(
            "이거|그거|저거|이것|그것|저것|이걸|그걸|저걸|방금|아까|위에|위의|이 사진|그 사진|저 사진|"
            + "여기|거기|첫\\s*번째|두\\s*번째|세\\s*번째|[0-9]+\\s*번째|이 중|그 중|같은\\s*거|말고");

    // 문맥 해소 단계: 지시어가 있을 때만 직전 대화로 실제 대상을 치환한다.
    // 지시어가 없어 그 자체로 완결된 요청이면 LLM을 태우지 않고 원문을 그대로 통과시킨다 (검색 품질 보호).
    private ContextOutcome contextualize(ChatSession session, Long currentMsgId, String userText) {
        // 게이트: 맥락 의존 표현이 없으면 원문 그대로 (불필요한 재작성/과잉 되물음 방지)
        if (userText == null || !CONTEXT_REF.matcher(userText).find()) {
            return new ContextOutcome(userText, null);
        }
        try {
            String response = openAiWebClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "model", openAiModel,
                            "stream", false,
                            "messages", withRecentHistory(buildContextResolverPrompt(), session, currentMsgId, userText)
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            String content = extractAssistantContent(response);
            JsonNode node = mapper.readTree(content);
            String clarify = node.path("clarify").asText("").trim();
            if (!clarify.isBlank()) {
                return new ContextOutcome(null, clarify);
            }
            String resolved = node.path("resolved").asText("").trim();
            return new ContextOutcome(resolved.isBlank() ? userText : resolved, null);
        } catch (Exception e) {
            // 실패 시 원문 그대로 진행 (안전 폴백)
            log.warn("[AGENT CONTEXTUALIZE FAIL] fallback to original text", e);
            return new ContextOutcome(userText, null);
        }
    }

    private String buildContextResolverPrompt() {
        return """
너는 사진 관리 챗봇의 "지시어 해소기"다. 사용자의 마지막 메시지에서 앞 대화를 봐야만 알 수 있는 부분만 채워 넣는다.

가장 중요한 규칙 (기본 동작):
- 마지막 메시지가 그 자체로 이해되면 **절대 바꾸지 말고 원문 그대로 resolved에 넣어라.**
- 표현을 더 풍부하게/구체적으로/친절하게 바꾸지 마라. 단어를 추가하거나 부풀리지 마라.
- 예: "강아지 사진 찾아줘", "바다 사진", "커피 마시는 사람"은 이미 완결됐다 → 원문 그대로 resolved.

개입하는 경우 (지시어가 있을 때만):
- "이거/그거/저거/방금 그거/아까/위에/N번째" 처럼 앞 대화를 가리키는 표현이 있을 때만, 그 부분을 실제 대상으로 치환한다.
- 치환에 필요한 대상이 앞 대화에 정말 없을 때만 clarify로 되묻는다.

clarify는 최후의 수단이다:
- 지시어가 없고 그 자체로 뜻이 통하면 절대 clarify 하지 마라. (품종/상황을 더 물어보는 것도 금지)
- 확신이 안 서면 clarify 대신 원문 그대로 resolved로 통과시켜라.

출력은 반드시 JSON 하나만:
{"resolved":"<요청 그대로 또는 지시어만 치환한 것>"}  또는  {"clarify":"<되물을 짧은 질문>"}

예시:
"강아지 사진 찾아줘"        -> {"resolved":"강아지 사진 찾아줘"}
"바다 사진"                -> {"resolved":"바다 사진"}
"커피 마시는 사람"          -> {"resolved":"커피 마시는 사람"}
(직전: 강아지 사진 보여줌) "이거 폴더로 만들어줘" -> {"resolved":"강아지 사진으로 폴더 만들어줘"}
(직전 없음) "이거 찾아줘"    -> {"clarify":"어떤 사진을 찾아드릴까요?"}
""";
    }

    private Mono<String> classifyIntent(SessionCtx ctx, boolean hasPendingFolder) {
        return openAiWebClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", openAiModel,
                        "stream", false,
                        "messages", withRecentHistory(
                                buildIntentClassifierPrompt(hasPendingFolder),
                                ctx.session(), ctx.userMsg().getId(), ctx.resolvedText())
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

        // 편집 대상: 드래그로 넘어온 editSessionId 우선, 없으면 대화에 바인딩된 활성 편집 세션 사용
        Long resolvedEditSessionId = request.getEditSessionId() != null
                ? request.getEditSessionId()
                : activeEditSessionCache.get(request.getChatSessionId());

        if (resolvedEditSessionId == null) {
            return Flux.just(
                    ServerSentEvent.<String>builder().event("delta").data("편집할 사진을 먼저 선택해 주세요!").build(),
                    ServerSentEvent.<String>builder().event("done").data("ok").build()
            );
        }

        // 대화 단위로 활성 편집 세션 바인딩 → 이후 드래그 없이도 편집을 이어갈 수 있음
        activeEditSessionCache.put(request.getChatSessionId(), resolvedEditSessionId);
        final Long editSessionId = resolvedEditSessionId;

        return Mono.fromCallable(() -> {
                    // 멀티스텝 편집: GPT로 편집 단계 쪼개기
                    List<String> steps = splitEditSteps(ctx.resolvedText());
                    log.info("[AGENT EDIT] steps={}", steps);

                    StringBuilder resultUrl = new StringBuilder();

                    for (String step : steps) {
                        // 단계별 편집 + 실패 시 재시도
                        EditVersion version = editWithRetry(memberId, editSessionId, step);
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

                    DateDecomp decomp = decompose(ctx);
                    log.info("[AGENT SEARCH] date={}~{} text={}", decomp.from(), decomp.to(), decomp.text());
                    List<Long> photoIds = resolveCandidateIds(memberId, decomp, ctx.resolvedText(), false);
                    return toPhotoItems(photoIds);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(items -> {

                    String payloadJson = toJsonSafely(Map.of("items", items));
                    String assistantText = items.isEmpty()
                            ? "조건에 맞는 사진을 찾지 못했어요. 조금 더 구체적으로 말씀해 주시겠어요?"
                            : "요청하신 것과 비슷한 순으로 사진을 가져왔어요. 원하는 게 아니면 조금 더 자세히 말씀해 주세요.";
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

                    String keyword = buildFolderKeyword(ctx.resolvedText());
                    log.info("[AGENT FOLDER] keyword={}", keyword);

                    DateDecomp decomp = decompose(ctx);
                    List<Long> photoIds = resolveCandidateIds(memberId, decomp, ctx.resolvedText(), true);

                    Set<Long> sharedIds = Set.copyOf(photoFolderRepository.findSharedFolderPhotoIdsByMemberId(memberId));
                    List<Long> filtered = photoIds.stream().filter(id -> !sharedIds.contains(id)).toList();

                    List<PhotoSearchItemDTO> items = toPhotoItems(filtered);

                    if (!filtered.isEmpty()) {
                        pendingFolderCache.put(request.getChatSessionId(), keyword, filtered);
                    }

                    return new FolderPreviewResult(keyword, items);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(result -> {

                    String assistantText = result.items().isEmpty()
                            ? String.format("'%s' 관련된 사진을 찾지 못했어요. 조금 더 구체적으로 말씀해 주시겠어요?", result.keyword())
                            : String.format("'%s' 관련 사진 %d장을 찾았어요. 이 사진들로 폴더를 만들까요?",
                                    result.keyword(), result.items().size());
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

                    String keyword = buildFolderKeyword(ctx.resolvedText());
                    log.info("[AGENT SEARCH_AND_FOLDER] keyword={}", keyword);

                    DateDecomp decomp = decompose(ctx);
                    List<Long> photoIds = resolveCandidateIds(memberId, decomp, ctx.resolvedText(), true);

                    Set<Long> sharedIds = Set.copyOf(photoFolderRepository.findSharedFolderPhotoIdsByMemberId(memberId));
                    List<Long> filtered = photoIds.stream().filter(id -> !sharedIds.contains(id)).toList();

                    List<PhotoSearchItemDTO> items = toPhotoItems(filtered);

                    if (!filtered.isEmpty()) {
                        pendingFolderCache.put(request.getChatSessionId(), keyword, filtered);
                    }

                    return new FolderPreviewResult(keyword, items);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(result -> {

                    String assistantText = result.items().isEmpty()
                            ? String.format("'%s' 관련된 사진을 찾지 못했어요. 조금 더 구체적으로 말씀해 주시겠어요?", result.keyword())
                            : String.format("'%s' 관련 사진 %d장을 찾았어요. 이 사진들로 폴더를 만들까요?",
                                    result.keyword(), result.items().size());
                    saveAssistantMessage(ctx, assistantText);

                    String resultsJson = toJsonSafely(Map.of("items", result.items()));

                    return Flux.just(
                            ServerSentEvent.<String>builder().event("results").data(resultsJson).build(),
                            ServerSentEvent.<String>builder().event("delta").data(assistantText).build(),
                            ServerSentEvent.<String>builder().event("done").data("ok").build()
                    );
                });
    }

    // 폴더용 적응형 상대 컷: 최고 점수(top) 대비 FOLDER_CUT_RATIO 이상만 남긴다 (쿼리별 자동 적응).
    // top이 FOLDER_MIN_TOP 미만이면 관련 사진이 없다고 보고 빈 결과를 반환한다.
    // hits는 점수 내림차순(Qdrant) 이므로 hits.get(0)이 최고 점수.
    private List<Long> folderCandidateIds(List<SearchHitDTO> hits) {
        if (hits.isEmpty()) return List.of();
        double top = hits.get(0).score();
        if (top < FOLDER_MIN_TOP) return List.of();
        double cut = FOLDER_CUT_RATIO * top;
        return hits.stream()
                .filter(h -> h.score() >= cut)
                .map(SearchHitDTO::postId)
                .toList();
    }

    // ── 날짜 + 대상 분해 / 라우팅 ────────────────────────────
    private record DateDecomp(LocalDate from, LocalDate to, String text) {
        boolean hasDate() { return from != null && to != null; }
        boolean hasSemantic() { return text != null && !text.isBlank(); }
    }

    // 요청에서 날짜 조건(어제/지난주 등)과 나머지 대상 텍스트를 분리한다. (오늘=KST 기준)
    private DateDecomp decompose(SessionCtx ctx) {
        try {
            String response = openAiWebClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "model", openAiModel,
                            "stream", false,
                            "messages", List.of(
                                    Map.of("role", "system", "content", buildDateDecompPrompt(LocalDate.now(SEOUL).toString())),
                                    Map.of("role", "user", "content", ctx.resolvedText())
                            )
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            JsonNode node = mapper.readTree(extractAssistantContent(response));
            LocalDate from = parseDateOrNull(node.path("dateFrom").asText(""));
            LocalDate to = parseDateOrNull(node.path("dateTo").asText(""));
            return new DateDecomp(from, to, node.path("text").asText("").trim());
        } catch (Exception e) {
            log.warn("[AGENT DECOMPOSE FAIL] fallback: 날짜 없음", e);
            return new DateDecomp(null, null, ctx.resolvedText());
        }
    }

    private LocalDate parseDateOrNull(String s) {
        if (s == null || s.isBlank() || "null".equalsIgnoreCase(s.trim())) return null;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return null; }
    }

    // 검색/폴더 공통: 분해 결과로 후보 photoId 목록을 만든다.
    // 날짜만 → MySQL shotAt 범위(전수). 그 외 → 벡터검색(+날짜필터), forFolder면 적응형 컷.
    private List<Long> resolveCandidateIds(Long memberId, DateDecomp d, String rawResolvedText, boolean forFolder) {
        if (d.hasDate() && !d.hasSemantic()) {
            LocalDateTime from = d.from().atStartOfDay();
            LocalDateTime to = d.to().plusDays(1).atStartOfDay();
            return photoRepository.findByMemberAndShotAtBetween(memberId, from, to)
                    .stream().map(Photo::getId).toList();
        }
        String q = buildSearchQuery(d.hasSemantic() ? d.text() : rawResolvedText);
        Long fromMs = null, toMs = null;
        if (d.hasDate()) {
            fromMs = d.from().atStartOfDay(SEOUL).toInstant().toEpochMilli();
            toMs = d.to().plusDays(1).atStartOfDay(SEOUL).toInstant().toEpochMilli();
        }
        int k = forFolder ? FOLDER_CANDIDATE_K : SEARCH_TOP_K;
        List<SearchHitDTO> hits = searchWorkerClient.searchText(
                new TextSearchRequestDTO(q, k, memberId, fromMs, toMs)).hits();
        return forFolder ? folderCandidateIds(hits) : hits.stream().map(SearchHitDTO::postId).toList();
    }

    private String buildDateDecompPrompt(String todayKst) {
        return """
너는 사진 검색 요청에서 날짜 조건과 나머지 요청을 분리하는 파서다.
오늘 날짜(KST)는 %s 이다.

규칙:
- 날짜/기간 표현(어제, 오늘, 지난주, 지난달, N월, YYYY-MM-DD 등)이 있으면 오늘 기준으로 dateFrom/dateTo(YYYY-MM-DD, 포함 범위)를 계산한다.
- 날짜 표현을 뺀 "찾으려는 대상"을 text에 담는다. 대상이 따로 없으면 text는 "".
- 날짜 표현이 없으면 dateFrom/dateTo는 null.

출력은 반드시 JSON 하나만:
{"dateFrom": "YYYY-MM-DD 또는 null", "dateTo": "YYYY-MM-DD 또는 null", "text": "나머지 요청"}

예)
"어제 찍은 강아지 사진 찾아줘" -> {"dateFrom":"2026-07-29","dateTo":"2026-07-29","text":"강아지 사진 찾아줘"}
"어제 사진 찾아줘"            -> {"dateFrom":"2026-07-29","dateTo":"2026-07-29","text":""}
"지난주 사진 폴더 만들어줘"     -> {"dateFrom":"2026-07-20","dateTo":"2026-07-26","text":""}
"강아지 사진 찾아줘"          -> {"dateFrom":null,"dateTo":null,"text":"강아지 사진 찾아줘"}
""".formatted(todayKst);
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

    // resolvedText: 문맥 해소된 실제 실행용 요청 / clarifyQuestion: 되물어야 하면 그 질문(아니면 null)
    private record SessionCtx(ChatSession session, ChatMessage userMsg, String resolvedText, String clarifyQuestion) {}

    private record FolderResult(Long folderId, String folderName, int photoCount) {}

    private record FolderPreviewResult(String keyword, List<PhotoSearchItemDTO> items) {}
}
