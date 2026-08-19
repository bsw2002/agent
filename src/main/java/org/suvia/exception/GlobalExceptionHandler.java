package org.suvia.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.suvia.agent.exception.AgentExecutionException;
import org.suvia.common.BaseResponse;
import org.suvia.common.ResultUtils;

import java.util.UUID;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<?>> businessExceptionHandler(BusinessException e) {
        log.warn("Business request rejected: code={}", e.getCode());
        return ResponseEntity.badRequest().body(ResultUtils.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponse<?>> validationExceptionHandler(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(ResultUtils.error(40000, "请求参数不合法"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<BaseResponse<?>> illegalArgumentExceptionHandler(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ResultUtils.error(40000, e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<BaseResponse<?>> accessDeniedExceptionHandler(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ResultUtils.error(40300, "无权访问该资源"));
    }

    @ExceptionHandler(AgentExecutionException.class)
    public ResponseEntity<BaseResponse<?>> agentExecutionExceptionHandler(AgentExecutionException e) {
        String incidentId = UUID.randomUUID().toString();
        log.error("Agent execution failed: incidentId={}, type={}", incidentId, e.getClass().getSimpleName());
        log.debug("Agent incident details: incidentId={}", incidentId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResultUtils.error(ErrorCode.SYSTEM_ERROR, "Agent 执行失败，事件编号：" + incidentId));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<BaseResponse<?>> runtimeExceptionHandler(RuntimeException e) {
        String incidentId = UUID.randomUUID().toString();
        log.error("Unhandled runtime failure: incidentId={}, type={}", incidentId, e.getClass().getSimpleName());
        log.debug("Runtime incident details: incidentId={}", incidentId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误，事件编号：" + incidentId));
    }

}
