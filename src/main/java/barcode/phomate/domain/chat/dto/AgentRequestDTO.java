package barcode.phomate.domain.chat.dto;

import lombok.Getter;

import java.util.List;

@Getter
public class AgentRequestDTO {

    // 기존 채팅 세션 id
    private Long chatSessionId;

    // 편집 챗봇에서 넘어올 때만 사용 (검색/폴더는 null)
    private Long editSessionId;

    // 사용자가 보낸 자연어
    private String userText;

    // 폴더 생성 확정(confirm) 시, 사용자가 후보 중 최종 선택한 사진 id 목록.
    // null 또는 빈 값이면 후보 전체로 폴더를 만든다.
    private List<Long> selectedPhotoIds;
}
