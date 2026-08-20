package com.skala.helpdesk.web;

import java.util.List;
import java.util.Map;

import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public AdminController(VectorStore vectorStore, WithdrawalRequestRepository requests) {
        this.vectorStore = vectorStore;
        this.requests = requests;
    }

    /**
     * TODO(B, Phase 2 심화): 인제스트는 성공 메시지가 아니라 결과물로 확인한다.
     * {@code vectorStore.similaritySearch(...)}로 검색해 source·version·score·미리보기를
     * 반환한다(교안 p.315 코드 그대로). 여기서 안 잡으면 Phase 3에서 원인을 못 찾는다.
     */
    @GetMapping("/api/admin/chunks")
    @Operation(summary = "인제스트된 청크 검사", description = "무엇이 들어갔는지 눈으로 확인한다(p.315).")
    public List<Map<String, Object>> inspectChunks(@RequestParam String q,
                                                    @RequestParam(defaultValue = "5") int topK) {
        var hits = vectorStore.similaritySearch(SearchRequest.builder().query(q).topK(topK).build());
        if (hits == null) {
            return List.of();
        }
        return hits.stream().map(d -> Map.<String, Object>of(
                "source", String.valueOf(d.getMetadata().get("source")),
                "version", String.valueOf(d.getMetadata().get("version")),
                "score", d.getScore(),
                "preview", d.getText().substring(0, Math.min(160, d.getText().length()))
        )).toList();
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
