package com.xgateai.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * HttpResponse 统一响应体
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Data
public class HttpResponse {

    public static final int SUCCESS = 200;
    public static final int FAILED = 400;

    @JsonProperty("code")
    private int code;

    @JsonProperty("message")
    private String message;

    @JsonProperty("result")
    private Object result;

    public static HttpResponse object(Object data) {
        return objectForMessage(data, "success");
    }

    public static HttpResponse objectForMessage(Object data, String message) {
        HttpResponse response = new HttpResponse();
        response.setCode(SUCCESS);
        response.setMessage(message);
        response.setResult(data);
        return response;
    }

    public static <T> HttpResponse list(List<T> list, long total, long pageNum, long pageSize) {
        return listForMessage(list, total, pageNum, pageSize, "success");
    }

    public static <T> HttpResponse listForMessage(List<T> list, long total, long pageNum,
                                                   long pageSize, String message) {
        HttpResponse response = new HttpResponse();
        response.setCode(SUCCESS);
        response.setMessage(message);
        response.setResult(Map.of(
                "total", total,
                "page_num", pageNum,
                "page_size", pageSize,
                "list", list
        ));
        return response;
    }

    public static HttpResponse success() {
        return successForMessage("success");
    }

    public static HttpResponse successForMessage(String message) {
        HttpResponse response = new HttpResponse();
        response.setCode(SUCCESS);
        response.setMessage(message);
        response.setResult(null);
        return response;
    }

    public static HttpResponse error(String message) {
        HttpResponse response = new HttpResponse();
        response.setCode(FAILED);
        response.setMessage(message);
        response.setResult(null);
        return response;
    }
}
