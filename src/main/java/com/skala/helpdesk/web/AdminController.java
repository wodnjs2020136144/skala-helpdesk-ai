package com.skala.helpdesk.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * <p>이 컨트롤러 전체에 {@code @PreAuthorize("hasRole('ADMIN')")}를 적용해 학생 계정과
 * AI Tool이 승인·진단 경로에 접근하지 못하게 한다.
 *
 * <p>참고: {@code day3-consult-agent/web/AdminController.java}, 교안 Phase 2 심화 코드(p.315)
 */
@RestController
@Tag(name = "HelpDesk · 관리자(학사팀)")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final String APPLIED_THRESHOLD_HEADER = "X-Applied-Similarity-Threshold";

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
    @Operation(summary = "인제스트된 청크 검사",
            description = "무엇이 들어갔는지 눈으로 확인한다. threshold=0이면 임계값 없이 검색한다(p.315).")
    public ResponseEntity<?> inspectChunks(@RequestParam(required = false) String q,
                                           @RequestParam(required = false) String topK,
                                           @RequestParam(required = false) String threshold) {
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
        if (requestedTopK < 1 || requestedTopK > props.rag().inspectionMaxTopK()) {
            return badRequest("topK는 1 이상 %d 이하여야 합니다."
                    .formatted(props.rag().inspectionMaxTopK()));
        }
        double requestedThreshold;
        try {
            requestedThreshold = threshold == null
                    ? props.rag().threshold()
                    : Double.parseDouble(threshold);
        }
        catch (NumberFormatException e) {
            return badRequest("threshold는 숫자여야 합니다.");
        }
        if (!Double.isFinite(requestedThreshold)
                || requestedThreshold < 0.0 || requestedThreshold > 1.0) {
            return badRequest("threshold는 0 이상 1 이하여야 합니다.");
        }

        var hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(q.trim())
                .topK(requestedTopK)
                .similarityThreshold(requestedThreshold)
                .build());
        if (hits == null) {
            return ok(List.of(), requestedThreshold);
        }
        var result = hits.stream()
                .filter(Objects::nonNull)
                .map(this::chunkView)
                .toList();
        return ok(result, requestedThreshold);
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

    private ResponseEntity<List<Map<String, Object>>> ok(List<Map<String, Object>> result,
                                                          double appliedThreshold) {
        return ResponseEntity.ok()
                .header(APPLIED_THRESHOLD_HEADER, Double.toString(appliedThreshold))
                .body(result);
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
