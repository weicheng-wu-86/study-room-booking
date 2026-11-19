package com.studyroom.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把各类异常统一转换成 {code, message} 结构，前端只需处理一种错误格式。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.getCode()).body(Result.fail(e.getCode(), e.getMessage()));
    }

    /** 请求体校验失败（@Valid），取第一条字段错误返回 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe == null ? "请求参数不合法" : fe.getDefaultMessage();
        return ResponseEntity.status(400).body(Result.fail(400, msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(400).body(Result.fail(400, "请求体格式错误"));
    }

    /** 兜底：未被业务代码捕获的唯一索引冲突 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicate(DuplicateKeyException e) {
        log.warn("唯一索引冲突: {}", e.getMessage());
        return ResponseEntity.status(409).body(Result.fail(409, "数据已存在或已被占用，请刷新后重试"));
    }

    /** 路径或查询参数类型不匹配，例如把字母传给了数字型的 id */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(400).body(Result.fail(400, "参数 " + e.getName() + " 格式错误"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        // Spring MVC 自身的异常（404、405、缺少参数等）都实现了 ErrorResponse，沿用其状态码
        if (e instanceof ErrorResponse er) {
            int status = er.getStatusCode().value();
            return ResponseEntity.status(status).body(Result.fail(status, "请求无法处理（" + status + "）"));
        }
        log.error("未处理的异常", e);
        return ResponseEntity.status(500).body(Result.fail(500, "服务器内部错误"));
    }
}
