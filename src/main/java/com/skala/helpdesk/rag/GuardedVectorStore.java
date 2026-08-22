package com.skala.helpdesk.rag;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * 담당: A(첫 번째 책임자) · 리뷰: B — Phase 7, 레드팀 7번(문서 기반 간접 인젝션).
 *
 * <p>{@link VectorStore}를 감싸 <b>검색 결과가 나오는 순간</b> {@link RetrievalGuard}로
 * 한 번 더 거른다. 쓰기(add·delete)는 손대지 않고 그대로 위임한다 — 재색인 계약
 * ({@code IngestService}의 "문서 단위 삭제 후 재삽입")이 깨지면 안 되기 때문이다.
 *
 * <p><b>이건 2차 방어다.</b> 주 방어는 {@code IngestService.injectionRuleIn}이 인제스트 때
 * 문서 전체로 판정해 {@code injected} 메타데이터를 붙이고 {@code AiConfig}의 검색 필터가
 * 걸러내는 쪽이다. 여기는 <b>청크 하나</b>만 보므로, 지시문이 청크 경계에 걸려 갈리면
 * 뒷토막을 놓친다(실측으로 확인했다 — {@code injectionRuleIn} Javadoc 참고).
 *
 * <p><b>그런데도 남겨 두는 이유</b> — 인제스트를 거치지 않고 이미 저장돼 있는 청크가 있다.
 * {@code helpdesk-docs/}에서 문서 파일을 지우면 {@code IngestService}는 그 파일을 더 이상
 * 보지 못하므로 남은 행을 다시 표시하지도, 지우지도 못한다(레드팀 7번 실측 중 실제로
 * 겪었다 — 위조 문서를 지운 뒤에도 pgvector에 청크 2건이 남아 직접 DELETE해야 했다).
 * 그 상태에서 오염 청크가 검색에 걸리면 막는 건 여기뿐이다.
 *
 * <p><b>왜 Advisor가 아니라 여기인가</b> — 처음에는 RAG(300) 뒤에 order 310 Advisor를
 * 두려 했으나 {@code QuestionAnswerAdvisor} 소스를 열어 보고 접었다. 그쪽 {@code before()}는
 * 검색 직후 <b>문서 본문을 사용자 메시지에 이미 병합해서</b> 반환한다. 그 뒤에서 오염
 * 문서를 골라내 봐야 프롬프트에서 텍스트를 빼려면 증강 텍스트를 다시 렌더링해야 하고,
 * 원 질문이 Spring AI의 기본 템플릿({@code {query}\n\nContext information is below...})
 * 안에 묻혀 있어 문자열 파싱에 기대게 된다 — 라이브러리가 템플릿을 바꾸면 조용히 깨진다.
 *
 * <p>검색 경계에서 거르면 두 가지가 따라온다:
 * <ul>
 *   <li>오염 문서가 {@code QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS}에 애초에 들어가지
 *       않으므로, {@code HelpDeskService#sourcesFrom}이 그걸 출처로 표기하는 일도 함께 막힌다.</li>
 *   <li><b>Advisor 순서표를 건드리지 않는다</b> — 순서표는 A·B 공동 결정 사항이다(AGENTS.md).</li>
 * </ul>
 *
 * <p>이 데코레이터는 빈으로 등록하지 않고 {@code AiConfig#helpDeskClient} 안에서만 감싼다.
 * 그래서 {@code section == 'main'} 필터와 똑같은 경계가 생긴다 — <b>운영 검색은 걸러진 것만,
 * 관리자 진단({@code AdminController})은 원본 {@link VectorStore}로 전부 본다.</b> 오염된
 * 청크를 눈으로 봐야 진단이 되므로 이 경계가 맞다.
 */
public class GuardedVectorStore implements VectorStore {

    private final VectorStore delegate;
    private final RetrievalGuard guard;

    public GuardedVectorStore(VectorStore delegate, RetrievalGuard guard) {
        this.delegate = delegate;
        this.guard = guard;
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return guard.filter(delegate.similaritySearch(request));
    }

    @Override
    public void add(List<Document> documents) {
        delegate.add(documents);
    }

    @Override
    public void delete(List<String> idList) {
        delegate.delete(idList);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    /** 관측(Micrometer)·로그에서 원본 저장소와 같은 이름으로 보이게 둔다. */
    @Override
    public String getName() {
        return delegate.getName();
    }
}
