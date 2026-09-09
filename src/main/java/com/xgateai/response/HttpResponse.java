package com.xgateai.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * HttpResponse 统一响应体
 * <p>
 * 所有业务接口均以此格式封装返回，便于前端统一解析 code/message/result 三段结构。
 * 成功时 code=200；错误时由 {@link com.xgateai.exception.GlobalExceptionHandler} 统一处理。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Data
public class HttpResponse {

    /** 成功状态码 */
    public static final int SUCCESS = 200;
    /** 失败状态码 */
    public static final int FAILED = 400;

    /**
     * 业务状态码：200 表示成功，其他值表示失败
     */
    @JsonProperty("code")
    private int code;

    /**
     * 消息描述（成功时为 "success"，失败时为具体错误信息）
     */
    @JsonProperty("message")
    private String message;

    /**
     * 响应数据主体，类型根据业务场景而定
     */
    @JsonProperty("result")
    private Object result;

    /**
     * 工厂方法：返回含单条对象数据的成功响应
     *
     * @param data 业务数据对象
     * @return 封装后的 HttpResponse
     */
    public static HttpResponse object(Object data) {
        return objectForMessage(data, "success");
    }

    /**
     * 工厂方法：返回含单条对象数据的成功响应（可自定义消息）
     *
     * @param data    业务数据对象
     * @param message 自定义成功消息
     * @return 封装后的 HttpResponse
     */
    public static HttpResponse objectForMessage(Object data, String message) {
        HttpResponse response = new HttpResponse();
        response.setCode(SUCCESS);
        response.setMessage(message);
        response.setResult(data);
        return response;
    }

    /**
     * 工厂方法：返回分页列表数据的成功响应
     *
     * @param list     数据列表
     * @param total    总记录数
     * @param pageNum  当前页码
     * @param pageSize 每页条数
     * @param <T>      列表元素类型
     * @return 封装后的 HttpResponse
     */
    public static <T> HttpResponse list(List<T> list, long total, long pageNum, long pageSize) {
        return listForMessage(list, total, pageNum, pageSize, "success");
    }

    /**
     * 工厂方法：返回分页列表数据的成功响应（可自定义消息）
     *
     * @param list     数据列表
     * @param total    总记录数
     * @param pageNum  当前页码
     * @param pageSize 每页条数
     * @param message  自定义成功消息
     * @param <T>      列表元素类型
     * @return 封装后的 HttpResponse
     */
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

    /**
     * 工厂方法：返回纯成功响应（无数据体）
     *
     * @return 封装后的 HttpResponse
     */
    public static HttpResponse success() {
        return successForMessage("success");
    }

    /**
     * 工厂方法：返回纯成功响应（可自定义消息）
     *
     * @param message 自定义成功消息
     * @return 封装后的 HttpResponse
     */
    public static HttpResponse successForMessage(String message) {
        HttpResponse response = new HttpResponse();
        response.setCode(SUCCESS);
        response.setMessage(message);
        response.setResult(null);
        return response;
    }

    /**
     * 工厂方法：返回错误响应
     *
     * @param message 错误提示信息
     * @return 封装后的 HttpResponse
     */
    public static HttpResponse error(String message) {
        HttpResponse response = new HttpResponse();
        response.setCode(FAILED);
        response.setMessage(message);
        response.setResult(null);
        return response;
    }
}
