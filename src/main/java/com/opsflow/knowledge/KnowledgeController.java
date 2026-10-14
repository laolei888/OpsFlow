package com.opsflow.knowledge;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
@RestController
@RequestMapping("/api/v1/knowledge/articles")
@RequiredArgsConstructor
public class KnowledgeController {
    private final KnowledgeService knowledgeService;
    private final KnowledgeFeedbackService feedbackService;
    private final KnowledgeAnalyticsService analyticsService;

    @GetMapping("/analytics")
    public ApiResponse<KnowledgeAnalyticsService.KnowledgeAnalytics> analytics(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(analyticsService.summarize(principal.tenantId()));
    }

    @GetMapping
    public ApiResponse<Page<KnowledgeArticle>> search(@RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "20") long size,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String category,
                                                       @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(knowledgeService.search(page, size, keyword, category, principal.tenantId(),
                principal.hasAnyRole("AGENT", "ADMIN")));
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeArticle> get(@PathVariable Long id,
                                             @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(knowledgeService.getPublished(id, principal.tenantId()));
    }

    @PostMapping
    public ApiResponse<KnowledgeArticle> create(@Valid @RequestBody CreateArticleRequest request,
                                                @AuthenticationPrincipal AuthPrincipal principal) {
        KnowledgeService.CreateArticleCommand command = new KnowledgeService.CreateArticleCommand(
                request.title(), request.slug(), request.category(), request.content());
        return ApiResponse.ok(knowledgeService.create(command, principal.id(), principal.tenantId()), "文章已保存为草稿");
    }

    @PutMapping("/{id}")
    public ApiResponse<KnowledgeArticle> update(@PathVariable Long id,
                                                @Valid @RequestBody UpdateArticleRequest request,
                                                @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        KnowledgeService.UpdateArticleCommand command = new KnowledgeService.UpdateArticleCommand(
                request.title(), request.slug(), request.category(), request.content());
        return ApiResponse.ok(knowledgeService.update(id, command, principal.id(), principal.tenantId()), "文章已更新");
    }

    @GetMapping("/{id}/feedback")
    public ApiResponse<KnowledgeFeedbackService.FeedbackSummary> feedback(@PathVariable Long id,
                                                                           @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(feedbackService.summary(id, principal.id(), principal.tenantId()));
    }

    @PostMapping("/{id}/feedback")
    public ApiResponse<KnowledgeFeedbackService.FeedbackSummary> submitFeedback(
            @PathVariable Long id,
            @Valid @RequestBody FeedbackRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(feedbackService.submit(id, principal.id(), principal.tenantId(), request.helpful()),
                "感谢你的反馈");
    }
    @DeleteMapping("/{id}/feedback")
    public ApiResponse<KnowledgeFeedbackService.FeedbackSummary> removeFeedback(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(feedbackService.remove(id, principal.id(), principal.tenantId()), "反馈已撤销");
    }
    @GetMapping("/{id}/versions")
    public ApiResponse<List<KnowledgeArticleVersion>> versions(@PathVariable Long id,
                                                                @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(knowledgeService.versions(id, principal.tenantId()));
    }
    @PostMapping("/{id}/versions/{versionId}/restore")
    public ApiResponse<KnowledgeArticle> restoreVersion(@PathVariable Long id,
                                                        @PathVariable Long versionId,
                                                        @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(knowledgeService.restoreVersion(id, versionId, principal.id(), principal.tenantId()),
                "历史版本已恢复为当前内容");
    }
    @PostMapping("/{id}/publish")
    public ApiResponse<KnowledgeArticle> publish(@PathVariable Long id,
                                                 @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(knowledgeService.publish(id, principal.id(), principal.tenantId()), "文章已发布");
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<KnowledgeArticle> archive(@PathVariable Long id,
                                                 @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(knowledgeService.archive(id, principal.id(), principal.tenantId()), "文章已归档");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<KnowledgeArticle> restore(@PathVariable Long id,
                                                 @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(knowledgeService.restore(id, principal.id(), principal.tenantId()), "文章已恢复为草稿");
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以管理知识库");
        }
    }

    public record FeedbackRequest(@NotNull Boolean helpful) { }
    public record UpdateArticleRequest(
            @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 180) String slug,
            @NotBlank @Size(max = 64) String category,
            @NotBlank @Size(max = 50000) String content) { }

    public record CreateArticleRequest(
            @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 180) String slug,
            @NotBlank @Size(max = 64) String category,
            @NotBlank @Size(max = 50000) String content) { }
}