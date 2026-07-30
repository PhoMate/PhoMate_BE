package barcode.phomate.domain.chat.application;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 대화(chatSession) 단위로 "현재 편집 중인 editSession"을 기억한다.
 * 드래그로 편집이 시작되면 바인딩되고, 이후 드래그 없이도 편집을 이어갈 수 있다.
 * 편집이 아닌 다른 동작(검색/폴더 등)을 하면 바인딩을 해제하여,
 * 이후 같은 사진을 다시 편집할 때는 예전 세션을 재개하지 않고 새로 시작한다.
 */
@Component
public class ActiveEditSessionCache {

    private final Map<Long, Long> store = new ConcurrentHashMap<>();

    public void put(Long chatSessionId, Long editSessionId) {
        store.put(chatSessionId, editSessionId);
    }

    public Long get(Long chatSessionId) {
        return store.get(chatSessionId);
    }

    public void clear(Long chatSessionId) {
        store.remove(chatSessionId);
    }
}
