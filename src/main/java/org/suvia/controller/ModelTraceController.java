package org.suvia.controller;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.suvia.common.BaseResponse;
import org.suvia.common.ResultUtils;
import org.suvia.security.RequestIdentity;
import org.suvia.security.RequestIdentityResolver;
import org.suvia.trace.ModelCallRecord;
import org.suvia.trace.ModelCallScene;
import org.suvia.trace.ModelCallStatus;
import org.suvia.trace.ModelTraceService;
import org.suvia.trace.TraceDetails;
import org.suvia.trace.TraceEvaluationRecord;
import org.suvia.trace.TraceEvaluationRequest;
import org.suvia.trace.TraceLabelRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/ai/traces")
public class ModelTraceController {

    private final ModelTraceService traceService;
    private final RequestIdentityResolver identityResolver;

    public ModelTraceController(ModelTraceService traceService, RequestIdentityResolver identityResolver) {
        this.traceService = traceService;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/{traceId}")
    public BaseResponse<TraceDetails> getTrace(@PathVariable UUID traceId, Authentication authentication) {
        return ResultUtils.success(traceService.getTrace(traceId, identity(authentication)));
    }

    @GetMapping("/{traceId}/calls")
    public BaseResponse<List<ModelCallRecord>> getTraceCalls(
            @PathVariable UUID traceId,
            Authentication authentication
    ) {
        return ResultUtils.success(traceService.getTrace(traceId, identity(authentication)).modelCalls());
    }

    @GetMapping("/calls/{callId}")
    public BaseResponse<ModelCallRecord> getCall(@PathVariable UUID callId, Authentication authentication) {
        return ResultUtils.success(traceService.getCall(callId, identity(authentication)));
    }

    @GetMapping
    public BaseResponse<List<ModelCallRecord>> search(
            @RequestParam(required = false) String chatId,
            @RequestParam(required = false) ModelCallScene scene,
            @RequestParam(required = false) ModelCallStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endAt,
            @RequestParam(required = false) Integer limit,
            Authentication authentication
    ) {
        return ResultUtils.success(traceService.search(
                identity(authentication), chatId, scene, status, startAt, endAt, limit
        ));
    }

    @PostMapping("/{traceId}/evaluate")
    public BaseResponse<TraceEvaluationRecord> evaluate(
            @PathVariable UUID traceId,
            @RequestBody(required = false) TraceEvaluationRequest request,
            Authentication authentication
    ) {
        boolean includeLlmJudge = request != null && request.includeLlmJudge();
        return ResultUtils.success(traceService.evaluate(traceId, identity(authentication), includeLlmJudge));
    }

    @PutMapping("/{traceId}/label")
    public BaseResponse<TraceEvaluationRecord> label(
            @PathVariable UUID traceId,
            @Valid @RequestBody TraceLabelRequest request,
            Authentication authentication
    ) {
        return ResultUtils.success(traceService.label(traceId, identity(authentication), request));
    }

    private RequestIdentity identity(Authentication authentication) {
        return identityResolver.resolve(authentication);
    }
}
