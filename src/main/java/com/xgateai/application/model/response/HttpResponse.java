package com.xgateai.application.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * HttpResponse 统一返回数据模型
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
public class HttpResponse {

    /**
     * 成功状态码
     */
    public static final int SUCCESS = 200;
    /**
     * 失败状态码
     */
    public static final int FAILED = 400;

    /**
     * 状态码
     */
    @JsonProperty("code")
    private int code;

    /**
     * 消息
     */
    @JsonProperty("message")
    private String message;

    /**
     * 返回数据
     */
    @JsonProperty("result")
    private Object result;

    /**
     * 构建成功响应，携带返回数据和默认成功消息
     */
    public static HttpResponse object(Object object) {
        return objectForMessage(object, "success");
    }

    /**
     * 构建成功响应，携带返回数据和自定义消息
     */
    public static HttpResponse objectForMessage(Object object, String message) {
        HttpResponse respond = new HttpResponse();
        respond.setCode(SUCCESS);
        respond.setMessage(message);
        respond.setResult(object);
        return respond;
    }

    /**
     * 构建成功响应，携带分页列表数据和默认成功消息
     */
    public static <T> HttpResponse list(List<T> list, long total, long page_num, long page_size) {
        return listForMessage(list, total, page_num, page_size, "success");
    }

    /**
     * 构建成功响应，携带分页列表数据和自定义消息
     */
    public static <T> HttpResponse listForMessage(List<T> list, long total, long page_num, long page_size, String message) {
        HttpResponse respond = new HttpResponse();
        respond.setCode(SUCCESS);
        respond.setMessage(message);
        respond.setResult(Map.of(
                "total", total,
                "page_num", page_num,
                "page_size", page_size,
                "list", list
        ));
        return respond;
    }

    /**
     * 构建成功响应，不携带数据，使用默认成功消息
     */
    public static HttpResponse success() {
        return successForMessage("success");
    }

    /**
     * 构建成功响应，不携带数据，使用自定义消息
     */
    public static HttpResponse successForMessage(String message) {
        HttpResponse respond = new HttpResponse();
        respond.setCode(SUCCESS);
        respond.setMessage(message);
        respond.setResult(null);
        return respond;
    }

    /**
     * 构建失败响应，携带错误消息
     */
    public static HttpResponse error(String message) {
        HttpResponse respond = new HttpResponse();
        respond.setCode(FAILED);
        respond.setMessage(message);
        respond.setResult(null);
        return respond;
    }
}
