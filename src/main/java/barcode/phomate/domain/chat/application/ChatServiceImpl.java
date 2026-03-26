package barcode.phomate.domain.chat.application;

import barcode.phomate.domain.chat.domain.entity.ChatMessage;
import barcode.phomate.domain.chat.domain.entity.ChatSession;
import barcode.phomate.domain.chat.domain.repository.ChatMessageRepository;
import barcode.phomate.domain.chat.domain.repository.ChatSessionRepository;
import barcode.phomate.domain.chat.dto.ChatSearchStreamRequestDTO;
import barcode.phomate.domain.chat.dto.ChatSendResponseDTO;
import barcode.phomate.domain.chat.dto.ChatStreamRequestDTO;
import barcode.phomate.domain.chat.dto.PhotoSearchItemDTO;
import barcode.phomate.domain.edit.application.EditService;
import barcode.phomate.domain.edit.domain.entity.EditVersion;
import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.domain.photo.domain.entity.Photo;
import barcode.phomate.domain.photo.domain.repository.PhotoRepository;
import barcode.phomate.global.exception.ForbiddenException;
import barcode.phomate.global.exception.NotFoundException;
import barcode.phomate.global.fastapi.client.SearchWorkerClient;
import barcode.phomate.global.fastapi.dto.SearchResponseDTO;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatServiceImpl implements ChatService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MemberRepository memberRepository;
    private final EditService editService;
    private final WebClient openAiWebClient;
    private final SearchWorkerClient searchWorkerClient;
    private final PhotoRepository photoRepository;

    private static final int SEARCH_TOP_K = 50;

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.openai.model:gpt-4o-mini}")
    private String openAiModel;

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

    // 3. 텍스트 스트리밍
    @Override
    public Flux<ServerSentEvent<String>> streamText(ChatStreamRequestDTO request) {

        // 1. 세션 검증 + 권한 확인 + user 메시지 저장
        return Mono.fromCallable(() -> {

                    // 세션 존재 여부 확인
                    ChatSession session = chatSessionRepository.findById(request.getChatSessionId())
                            .orElseThrow(() -> new NotFoundException("세션을 찾을 수 없습니다."));

                    // 세션 권한 확인
                    if (!session.getMember().getId().equals(request.getMemberId())) {
                        throw new ForbiddenException("권한이 없습니다.");
                    }

                    // 사용자가 보냈던 userText를 나중에 다시 볼 수 있게하는 역할(DB 저장)
                    ChatMessage userMsg = chatMessageRepository.save(ChatMessage.builder()
                            .chatSession(session)
                            .parent(null)
                            .content(request.getUserText())
                            .build());

                    // 세션 + user 메시지
                    return new VerifiedContext(session, userMsg);
                })
                .subscribeOn(Schedulers.boundedElastic()) // 별도 thread 할당
                .flatMapMany(context -> { // 하나의 결과(Mono) -> 여러개의 데이터 흐름(flux)

                    ChatSession session = context.session();
                    ChatMessage userMsg = context.userMsg();

                    // 답변(assistant:챗봇 메시지) 조각들 하나로 합침
                    StringBuilder assistantAcc = new StringBuilder();

                    // 로그용 변수 : 데이터 들어오는 횟수 측정용
                    AtomicInteger deltaCount = new AtomicInteger(0);
                    AtomicInteger dataLineCount = new AtomicInteger(0);
                    AtomicInteger doneCount = new AtomicInteger(0);

                    // webClient 써서 비동기 시작
                    Flux<String> textFlux =
                            openAiWebClient.post() // Post 메서드로 요청 시작
                                    .uri("/chat/completions")
                                    .contentType(MediaType.APPLICATION_JSON) // 서버로 보내는 body(본문)이 JSON이라는 뜻
                                    // 여기가 이제 한글자씩 데이터 주는 방식 시작점
                                    .accept(MediaType.TEXT_EVENT_STREAM) // SSE 형식 응답 요청
                                    .bodyValue(Map.of(
                                            "model", openAiModel,
                                            "stream", true,
                                            "messages", List.of(
                                                    Map.of("role", "system", "content", buildSystemPrompt()),
                                                    Map.of("role", "user", "content", request.getUserText())
                                            )
                                    )) // 모델명, 입력 텍스트, stream:true 옵션 데이터를 요청으로 보냄
                                    .retrieve() // 응답 body(본문) 내용 직접 추출 시작
                                    // 에러 처리 : OpenAI 서버 자체의 에러 발생 시의 내용 로그 & 에러 내기
                                    .onStatus(
                                            status -> status.is4xxClientError() || status.is5xxServerError(),
                                            response -> response.bodyToMono(String.class)
                                                    .defaultIfEmpty("")
                                                    .flatMap(body -> {
                                                        log.error("[OPENAI ERROR 로그가 발생했어요 !!] status={} body={}", response.statusCode(), body);
                                                        return Mono.error(new RuntimeException("OpenAI error: " + response.statusCode()));
                                                    })
                                    )
                                    // SSE 조각을 String으로 받아서 data: 붙어오는 라인만 파싱
                                    .bodyToFlux(String.class) // 한번에 계속 들어오는 응답 데이터들을 flux 통로로 바꿈
                                    .doOnNext(raw -> log.debug("[OPENAI RAW 로그가 발생했어요 !!] {}", raw)) // 들어오는 원본 데이터
                                    .flatMap(raw -> Flux.fromArray(raw.split("\n"))) // 줄 단위 분리
                                    .map(String::trim) // 공백 제거
                                    .filter(line -> !line.isBlank()) // 빈 줄 제거
                                    .map(this::normalizeOpenAiStreamPayload) // data: 붙어있든 말든 딱 필요한 JSON만 남김
                                    .filter(data -> data != null && !data.isBlank())
                                    .doOnNext(data -> {
                                        dataLineCount.incrementAndGet();
                                        if ("[DONE]".equals(data)) doneCount.incrementAndGet();
                                        log.debug("[OPENAI DATA !!] {}", data);
                                    })
                                    .filter(data -> !data.equals("[DONE]")) // OPENAI 스트리밍 끝 표시인 [DONE]은 걸러내기
                                    .map(this::readTreeSafely) // 걸러진 JSON 문자열 -> 자바 객체(JsonNode)
                                    .filter(n -> !n.isMissingNode()) // 변환 과정에서 에러 or 데이터 x 노드는 제외
                                    // 텍스트 조각만 걸러내고 나머지 무시
                                    .handle((node, sink) -> {
                                        JsonNode delta = node.path("choices").path(0).path("delta");
                                        JsonNode contentNode = delta.path("content");

                                        if (contentNode.isTextual()) {
                                            String text = contentNode.asText();
                                            if (text != null && !text.isEmpty()) {
                                                sink.next(text);
                                            }
                                        } else {
                                            // role-only chunk, finish chunk 등
                                            log.debug("[OPENAI DELTA NO-CONTENT] delta={}", delta);
                                        }
                                    })
                                    .cast(String.class)
                                    .doOnNext(t -> deltaCount.incrementAndGet()); // 텍스트 조각 전달된 횟수 셈

                    // 추가 시작
                    return textFlux
                            .map(text -> {
                                // 한 글자씩 올 때마다 모아둠
                                assistantAcc.append(text);
                                // 프론트엔드로는 즉시 전송
                                return ServerSentEvent.<String>builder().event("delta").data(text).build();
                            })
                            .concatWith(Mono.just(ServerSentEvent.<String>builder()
                                    .event("done")
                                    .data("ok")
                                    .build()))
                            // 스트림 실패/성공 여부에 관계없이 로그 남기고 DB에 저장
                            .doFinally(sig -> {
                                log.info(
                                        "[CHAT STREAM END] sig={} sessionId={} userMsgId={} dataLineCount={} doneCount={} deltaCount={} accLen={}",
                                        sig, session.getId(), userMsg.getId(),
                                        dataLineCount.get(), doneCount.get(), deltaCount.get(), assistantAcc.length()
                                );

                                // complete / cancel / error 모두 다
                                if (assistantAcc.length() == 0) {
                                    log.warn("[ASSIST SAVE SKIP] sig={} accLen=0 sessionId={} userMsgId={}",
                                            sig, session.getId(), userMsg.getId());
                                    return;
                                }
                                // DB 저장을 별도 thread에서 실행
                                Schedulers.boundedElastic().schedule(() -> {
                                    try {
                                        chatMessageRepository.save(ChatMessage.builder()
                                                .chatSession(session)
                                                .parent(userMsg)
                                                .content(assistantAcc.toString())
                                                .build());
                                        log.info("[ASSIST SAVED] sig={} sessionId={} userMsgId={} accLen={}",
                                                sig, session.getId(), userMsg.getId(), assistantAcc.length());
                                    } catch (Exception e) {
                                        log.error("[ASSIST SAVE FAIL] sessionId={} userMsgId={}", session.getId(), userMsg.getId(), e);
                                    }
                                });
                            })
                            // 프론트에서 스트림 종료 신호용 (저장 로직 x)
                            .concatWith(
                                    Mono.just(
                                            ServerSentEvent.<String>builder()
                                                    .event("done")
                                                    .data("ok")
                                                    .build()
                                    )
                            )
                            // 스트리밍 중 에러 발생 -> 프론트에 에러 이벤트 보내고 안전 종료 !!!!!
                            .onErrorResume(e -> {
                                log.error("[STREAM ERROR] sessionId={} userMsgId={}", session.getId(), userMsg.getId(), e);
                                return Flux.just(
                                        ServerSentEvent.<String>builder().event("error").data("stream_failed").build(),
                                        ServerSentEvent.<String>builder().event("done").data("ok").build()
                                );
                            });
                });
    }

    // 세션 검증 결과 컨텍스트
    // 필요한 이유 : flatMap은 원래 하나의 객체만 다음 단계로 보낼 수 있음
    // 세션 + userMsg 둘다 한번에 같이 다음 단계로 보내기 위해 필요
    private record VerifiedContext(
            ChatSession session, // 검증된 세션
            ChatMessage userMsg // DB에 방금 저장된 user 메시지
    ) {}

    private String buildSystemPrompt() {
        return "너는 사용자와 대화하는 챗봇이다. " +
                "짧고 명확하게 답해줘. " +
                "사용자가 사진 검색에 필요한 대화 외의 다른 대화를 하면, 사진 검색에 필요한 질문을 해주세요. 라고 답해.";
    }

    // 그냥 readTree()는 데이터 형식 잘못되면 exception 던져서 서버 멈춤
    // 필요한 이유 : 네트워크 불안정등으로 중간에 잘리거나 깨진 데이터 들어왔을때
    // 그냥 멈추는게 아니라 빈 노드 반환하고 다시 다음 데이터 받을 수 있게함
    // 빈 노드는 위에서 .filter(n -> !n.isMissingNode())에서 걸러짐
    private JsonNode readTreeSafely(String s) {
        try {
            // 들어온 문자열을 JSON 트리 구조로 바꿈
            return mapper.readTree(s);
        } catch (Exception e) {
            // exception 나면 서버 멈축기 대신 빈 노드(데이터 없음)를 대신 반환
            return mapper.missingNode();
        }
    }

    // data:가 붙어있으면 제거, 없으면 그대로 반환
    private String normalizeOpenAiStreamPayload(String raw) {
        if (raw == null) return null;

        // 문자열 앞뒤에 붙은 줄바꿈이나 공백 제거
        String trimmed = raw.trim();

        // data:가 안 붙어있고 JSON만 옴 -> 그대로 반환
        if (!trimmed.startsWith("data:")) return trimmed;

        // data:가 붙어 오면 data: 제거하고 공백까지 제거 -> 반환
        return trimmed.substring(5).trim();
    }

    @Override
    public Flux<ServerSentEvent<String>> streamSearch(Long memberId, ChatSearchStreamRequestDTO request) {

        return Mono.fromCallable(() -> {

                    ChatSession session = chatSessionRepository.findById(request.getChatSessionId())
                            .orElseThrow(() -> new NotFoundException("세션을 찾을 수 없습니다."));

                    if (!session.getMember().getId().equals(memberId)) {
                        throw new ForbiddenException("권한이 없습니다.");
                    }

                    ChatMessage userMsg = chatMessageRepository.save(ChatMessage.builder()
                            .chatSession(session)
                            .parent(null)
                            .content(request.getUserText())
                            .build());

                    return new VerifiedContext(session, userMsg);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ctx -> {

                    ChatSession session = ctx.session();
                    ChatMessage userMsg = ctx.userMsg();

                    // LLM: query planner
                    Mono<SearchPlan> planMono = openAiWebClient.post()
                            .uri("/chat/completions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of(
                                    "model", openAiModel,
                                    "stream", false,
                                    "messages", List.of(
                                            Map.of("role", "system", "content", buildQueryPlannerPrompt()),
                                            Map.of(
                                                    "role", "user",
                                                    "content", buildQueryPlannerUserInput(
                                                            session.getCurrentSearchQuery(),
                                                            request.getUserText()
                                                    )
                                            )
                                    )
                            ))
                            .retrieve()
                            .bodyToMono(String.class)
                            .map(this::extractAssistantContentFromChatCompletionsJson)
                            .map(this::parseSearchPlanJsonSafely);


                    // FastAPI: vector search (동기이므로 boundedElastic에서)
                    Mono<SearchContext> searchCtxMono = planMono
                            .publishOn(Schedulers.boundedElastic())
                            .map(plan -> {

                                String newQuery = plan.query();

                                // 검색 상태 갱신
                                session.updateSearchQuery(newQuery);
                                chatSessionRepository.save(session);

                                TextSearchRequestDTO reqDto = new TextSearchRequestDTO(
                                        newQuery,
                                        SEARCH_TOP_K,
                                        memberId,
                                        null,
                                        null
                                );

                                SearchResponseDTO sr = searchWorkerClient.searchText(reqDto);

                                List<Long> orderedIds = (sr == null || sr.hits() == null)
                                        ? List.of()
                                        : sr.hits().stream().map(h -> h.postId()).filter(Objects::nonNull).toList();

                                List<PhotoSearchItemDTO> items = toPhotoFeedByOrderedIds(orderedIds);
                                return new SearchContext(plan, items);

                            });


                    // SSE: results 1번 먼저 보내기
                    Flux<ServerSentEvent<String>> resultsEventFlux = searchCtxMono.flatMapMany(sc -> {
                        String payloadJson = toJsonSafely(Map.of("items", sc.items()));
                        return Flux.just(
                                ServerSentEvent.<String>builder()
                                        .event("results")
                                        .data(payloadJson)
                                        .build()
                        );
                    });

                    // LLM: reason streaming (delta)
                    Flux<ServerSentEvent<String>> reasonDeltaFlux = searchCtxMono.flatMapMany(sc -> {

                        List<String> topSummaries = sc.items().stream()
                                .limit(5)
                                .map(PhotoSearchItemDTO::description)
                                .filter(d -> d != null && !d.isBlank())
                                .toList();

                        String reasonSystem = buildReasonSystemPrompt();
                        String reasonUser = buildReasonUserPrompt(
                                request.getUserText(),
                                sc.plan().query(),
                                topSummaries
                        );


                        StringBuilder assistantAcc = new StringBuilder();
                        AtomicInteger deltaCount = new AtomicInteger(0);

                        Flux<String> textFlux =
                                openAiWebClient.post()
                                        .uri("/chat/completions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .accept(MediaType.TEXT_EVENT_STREAM)
                                        .bodyValue(Map.of(
                                                "model", openAiModel,
                                                "stream", true,
                                                "messages", List.of(
                                                        Map.of("role", "system", "content", reasonSystem),
                                                        Map.of("role", "user", "content", reasonUser)
                                                )
                                        ))
                                        .retrieve()
                                        .bodyToFlux(String.class)
                                        .flatMap(raw -> Flux.fromArray(raw.split("\n")))
                                        .map(String::trim)
                                        .filter(line -> !line.isBlank())
                                        .map(this::normalizeOpenAiStreamPayload)
                                        .filter(data -> data != null && !data.isBlank())
                                        .filter(data -> !data.equals("[DONE]"))
                                        .map(this::readTreeSafely)
                                        .filter(n -> !n.isMissingNode())
                                        .handle((node, sink) -> {
                                            JsonNode delta = node.path("choices").path(0).path("delta");
                                            JsonNode contentNode = delta.path("content");
                                            if (contentNode.isTextual()) {
                                                String text = contentNode.asText();
                                                if (text != null && !text.isEmpty()) sink.next(text);
                                            }
                                        })
                                        .cast(String.class)
                                        .doOnNext(t -> deltaCount.incrementAndGet());

                        // delta -> SSE + 누적
                        return textFlux
                                .map(t -> {
                                    assistantAcc.append(t);
                                    return ServerSentEvent.<String>builder().event("delta").data(t).build();
                                })
                                .doFinally(sig -> {
                                    // reason 저장 (assistant message)
                                    if (assistantAcc.length() == 0) return;

                                    Schedulers.boundedElastic().schedule(() -> {
                                        try {
                                            chatMessageRepository.save(ChatMessage.builder()
                                                    .chatSession(session)
                                                    .parent(userMsg)
                                                    .content(assistantAcc.toString())
                                                    .build());
                                            log.info("[SEARCH ASSIST SAVED] sessionId={} userMsgId={} accLen={} deltaCount={} sig={}",
                                                    session.getId(), userMsg.getId(), assistantAcc.length(), deltaCount.get(), sig);
                                        } catch (Exception e) {
                                            log.error("[SEARCH ASSIST SAVE FAIL] sessionId={} userMsgId={}",
                                                    session.getId(), userMsg.getId(), e);
                                        }
                                    });
                                });
                    });

                    // 전체 SSE 조립: results -> delta 스트림 -> done
                    return Flux.concat(
                                    resultsEventFlux,
                                    reasonDeltaFlux,
                                    Flux.just(ServerSentEvent.<String>builder().event("done").data("ok").build())
                            )
                            .onErrorResume(e -> {
                                log.error("[SEARCH STREAM ERROR] sessionId={} userMsgId={}",
                                        session.getId(), userMsg.getId(), e);
                                return Flux.just(
                                        ServerSentEvent.<String>builder().event("error").data("stream_failed").build(),
                                        ServerSentEvent.<String>builder().event("done").data("ok").build()
                                );
                            });
                });
    }

    private record SearchPlan(String query) {}

    private record SearchContext(SearchPlan plan, List<PhotoSearchItemDTO> items) {}



    private String buildQueryPlannerPrompt() {
        return """
You create one short English description for photo search.

Your job:
- Read the user's request in any language.
- Turn it into one short English description of what may be visible in the photo.

Output format:
{"query":"..."}

Rules:
- Return JSON only.
- Write in English.
- Keep it short and clear.
- Focus on what can be seen in a photo:
  people, objects, place, action, time, weather, colors, mood.
- Do not write abstract ideas.
- Do not explain anything.
- Do not use search-style phrases like "find", "show me", "search for".

Good examples:
{"query":"friends eating dinner indoors"}
{"query":"sunset beach with orange sky"}
{"query":"person drinking coffee in a cafe"}
{"query":"dog running on grass"}
{"query":"planet earth seen from space"}

Bad examples:
{"query":"find my memory"}
{"query":"happy feeling"}
{"query":"something nice"}
""";
    }



    private String buildReasonSystemPrompt() {
        return """
너는 사진 검색 챗봇이다.
사용자 요청과 실제 검색 결과(요약)를 바탕으로,
왜 이 결과들이 나왔는지 3~6문장으로 친절하게 설명해라.
불필요한 내용은 줄이고, 사용자가 다음에 무엇을 더 말하면 검색이 더 좋아지는지 마지막 문장에 제안해라.
""";
    }

    private String buildReasonUserPrompt(String userText, String query, List<String> summaries) {

        String block = summaries.isEmpty()
                ? "(설명 정보 없음)"
                : String.join("\n- ", summaries);

        return """
[사용자 요청]
""" + userText + """

[생성한 검색 쿼리]
""" + query + """

[검색 결과 요약(상위 결과 설명)]
- """ + block + """
""";
    }


    private List<PhotoSearchItemDTO> toPhotoFeedByOrderedIds(List<Long> orderedPhotoIds) {
        if (orderedPhotoIds == null || orderedPhotoIds.isEmpty()) return List.of();

        List<Photo> photos = photoRepository.findByIdIn(orderedPhotoIds);

        Map<Long, Photo> byId = photos.stream()
                .collect(Collectors.toMap(Photo::getId, p -> p));

        return orderedPhotoIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(p -> p.getPreviewKey() != null && !p.getPreviewKey().isBlank())
                .filter(p -> p.getThumbnailKey() != null && !p.getThumbnailKey().isBlank())
                .filter(p -> !"TEMP".equals(p.getPreviewKey()))
                .filter(p -> !"TEMP".equals(p.getThumbnailKey()))
                .filter(p -> !"TEMP".equals(p.getOriginalKey()))
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

    private String buildQueryPlannerUserInput(String currentQuery, String userText) {

        if (currentQuery == null || currentQuery.isBlank()) {
            return """
[사용자 요청]
""" + userText;
        }

        return """
[이전 검색 상태]
""" + currentQuery + """

[사용자 추가 요청]
""" + userText;
    }


    private String extractAssistantContentFromChatCompletionsJson(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private SearchPlan parseSearchPlanJsonSafely(String content) {
        try {
            JsonNode n = mapper.readTree(content);
            String q = n.path("query").asText("").trim();
            if (q.isBlank()) q = "";
            return new SearchPlan(q);
        } catch (Exception e) {
            return new SearchPlan(content == null ? "" : content.trim());
        }
    }

    private String toJsonSafely(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

}
