package com.skala.helpdesk.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.skala.helpdesk.config.HelpDeskProperties;
import com.skala.helpdesk.repository.WithdrawalRequestRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 담당: B(첫 번째 책임자) · 리뷰: A — Phase 2 심화(p.315) 청크 검사, Phase 4 승인(p.317).
 *
 * <p>수강철회 실제 승인은 사람(지도교수·학사팀)이 누른다. <b>이 경로는 도구 목록에
 * 없다.</b> 그래서 모델은 아무리 지시받아도 이 엔드포인트를 부를 수 없다 — "승인까지
 * 네가 해줘" 같은 레드팀 공격(검증 시나리오 ⑥)이 막히는 이유가 이것이다.
 *
 * <p>Phase 7에서 {@code SecurityConfig}가 완성되면 이 컨트롤러 전체에
 * {@code @PreAuthorize("hasRole('ADMIN')")}를 적용한다(지금은 미적용 — TODO).
 *
 * <p>참고: {@code day3-consult-agent/web/AdminController.java}, 교안 Phase 2 심화 코드(p.315)
 */
@RestController
@Tag(name = "HelpDesk · 관리자(학사팀)")
public class AdminController {

    private final VectorStore vectorStore;
    private final WithdrawalRequestRepository requests;
    private final HelpDeskProperties props;

    public AdminController(VectorStore vectorStore,
                           WithdrawalRequestRepository requests,
                           HelpDeskProperties props) {
        this.vectorStore = vectorStore;
        this.requests = requests;
        this.props = props;
    }

    /**
     * 인제스트는 성공 메시지가 아니라 검색 결과로 확인한다. Advisor와 같은 유사도 임계값을
     * 적용하고 source·docType·dept·version·score·미리보기를 반환한다. 여기서 메타데이터
     * 누락을 잡지 못하면 Phase 3의 출처 표기가 조용히 깨진다.
     */
    @GetMapping("/api/admin/chunks")
    @Operation(summary = "인제스트된 청크 검사", description = "무엇이 들어갔는지 눈으로 확인한다(p.315).")
    public ResponseEntity<?> inspectChunks(@RequestParam(required = false) String q,
                                           @RequestParam(required = false) String topK) {
        if (q == null || q.isBlank()) {
            return badRequest("검색어(q)를 입력해 주세요.");
        }
        int requestedTopK;
        try {
            requestedTopK = topK == null ? props.rag().topK() : Integer.parseInt(topK);
        }
        catch (NumberFormatException e) {
            return badRequest("topK는 숫자여야 합니다.");
        }
        if (requestedTopK < 1 || requestedTopK > props.rag().topK()) {
            return badRequest("topK는 1 이상 %d 이하여야 합니다.".formatted(props.rag().topK()));
        }

        var hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(q.trim())
                .topK(requestedTopK)
                .similarityThreshold(props.rag().threshold())
                .build());
        if (hits == null) {
            return ResponseEntity.ok(List.of());
        }
        var result = hits.stream()
                .filter(Objects::nonNull)
                .map(this::chunkView)
                .toList();
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> chunkView(Document document) {
        String text = Objects.requireNonNullElse(document.getText(), "");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("source", metadataText(document, "source"));
        view.put("docType", metadataText(document, "docType"));
        view.put("dept", metadataText(document, "dept"));
        view.put("version", metadataText(document, "version"));
        view.put("score", document.getScore());
        view.put("preview", text.substring(0, Math.min(160, text.length())));
        return view;
    }

    private String metadataText(Document document, String key) {
        return Objects.toString(document.getMetadata().get(key), "");
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    @GetMapping("/api/admin/withdrawal-requests/pending")
    @Operation(summary = "승인 대기 수강철회 목록")
    public List<?> pending() {
        return requests.pending();
    }

    @PostMapping("/api/admin/withdrawal-requests/{no}/approve")
    @Operation(summary = "사람이 누르는 승인", description = "도구 목록에 없으므로 모델은 이 경로에 닿을 수 없다.")
    public Map<String, Object> approve(@PathVariable String no) {
        return requests.approve(no)
                .<Map<String, Object>>map(r -> Map.of("no", r.no(), "status", r.status().name()))
                .orElse(Map.of("no", no, "status", "NOT_FOUND"));
    }
}
