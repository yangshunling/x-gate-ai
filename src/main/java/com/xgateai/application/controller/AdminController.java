package com.xgateai.application.controller;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xgateai.adminbridge.service.AdminService;
import com.xgateai.application.entity.CallLog;
import com.xgateai.application.model.dto.ChannelDTO;
import com.xgateai.application.model.dto.ProviderDTO;
import com.xgateai.application.model.response.HttpResponse;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * AdminController 管理端控制器：供 Web 控制台前端页面调用
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Slf4j
@RestController
@CrossOrigin
@RequestMapping("/admin")
public class AdminController {

    /**
     * 管理端服务
     */
    @Resource
    private AdminService adminService;

    /**
     * 查询全部上游 Provider 列表
     *
     * @return Provider 列表
     */
    @GetMapping("/providers")
    public HttpResponse providers() {
        return HttpResponse.object(adminService.listProviders());
    }

    /**
     * 新增上游 Provider
     *
     * @param dto Provider 参数
     * @return 保存成功
     */
    @PostMapping("/provider")
    public HttpResponse saveProvider(@RequestBody @Valid ProviderDTO dto) {
        adminService.saveProvider(dto);
        return HttpResponse.objectForMessage(null, "保存成功");
    }

    /**
     * 更新上游 Provider
     *
     * @param dto Provider 参数（apiKey 为空表示不修改）
     * @return 更新成功
     */
    @PutMapping("/provider")
    public HttpResponse updateProvider(@RequestBody @Valid ProviderDTO dto) {
        adminService.saveProvider(dto);
        return HttpResponse.objectForMessage(null, "更新成功");
    }

    /**
     * 删除上游 Provider
     *
     * @param id Provider ID
     * @return 删除成功
     */
    @DeleteMapping("/provider/{id}")
    public HttpResponse deleteProvider(@PathVariable Long id) {
        adminService.deleteProvider(id);
        return HttpResponse.successForMessage("删除成功");
    }

    /**
     * 连通性测试：探测指定上游 Provider 是否可用
     *
     * @param id Provider ID
     * @return 探测结果 JSON
     */
    @PostMapping("/provider/{id}/test")
    public HttpResponse testProvider(@PathVariable Long id) {
        return HttpResponse.object(adminService.testProvider(id));
    }

    /**
     * 查询全部对外客户 API Key 列表（含该 Key 可路由到的渠道）
     *
     * @return 客户 Key 列表
     */
    @GetMapping("/channels")
    public HttpResponse channels() {
        return HttpResponse.object(adminService.listChannels());
    }

    /**
     * 新增对外客户 API Key
     *
     * @param dto 客户 Key 参数
     * @return 保存成功，新增时返回自动生成的专属 Key
     */
    @PostMapping("/channel")
    public HttpResponse saveChannel(@RequestBody @Valid ChannelDTO dto) {
        String key = adminService.saveChannel(dto);
        return HttpResponse.objectForMessage(key, "保存成功");
    }

    /**
     * 更新对外客户 API Key
     *
     * @param dto 客户 Key 参数
     * @return 更新成功
     */
    @PutMapping("/channel")
    public HttpResponse updateChannel(@RequestBody @Valid ChannelDTO dto) {
        adminService.saveChannel(dto);
        return HttpResponse.objectForMessage(null, "更新成功");
    }

    /**
     * 删除对外客户 API Key
     *
     * @param id 客户 Key ID
     * @return 删除成功
     */
    @DeleteMapping("/channel/{id}")
    public HttpResponse deleteChannel(@PathVariable Long id) {
        adminService.deleteChannel(id);
        return HttpResponse.successForMessage("删除成功");
    }

    /**
     * 分页查询调用日志，支持多条件筛选
     */
    @GetMapping("/logs")
    public HttpResponse logs(@RequestParam(name = "publicModel", required = false) String publicModel,
                             @RequestParam(name = "apiKeyName", required = false) String apiKeyName,
                             @RequestParam(name = "model", required = false) String model,
                             @RequestParam(name = "dateFrom", required = false) String dateFrom,
                             @RequestParam(name = "dateTo", required = false) String dateTo,
                             @RequestParam(name = "status", required = false) Integer status,
                             @RequestParam(name = "pageNum", defaultValue = "1") int pageNum,
                             @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        Page<CallLog> page = adminService.queryLogs(publicModel, apiKeyName, model, dateFrom, dateTo, status, pageNum, pageSize);
        return HttpResponse.list(page.getRecords(), page.getTotal(), pageNum, pageSize);
    }

    /**
     * 查询 Token 用量趋势（按小时聚合）
     */
    @GetMapping("/dashboard/trend")
    public HttpResponse trend(@RequestParam(name = "hours", defaultValue = "24") int hours) {
        return HttpResponse.object(adminService.tokenTrend(hours));
    }

    /**
     * 查询模型分布统计
     */
    @GetMapping("/dashboard/models")
    public HttpResponse models() {
        return HttpResponse.object(adminService.modelStats());
    }

    /**
     * 查询控制台看板统计
     *
     * @return 看板统计 JSON
     */
    @GetMapping("/dashboard")
    public HttpResponse dashboard() {
        return HttpResponse.object(adminService.dashboard());
    }

    /**
     * 查询今日客户用量 Top5
     */
    @GetMapping("/dashboard/customers")
    public HttpResponse customers() {
        return HttpResponse.object(adminService.customerStats());
    }

    /**
     * 当前服务信息（局域网 IP 与端口），供前端渲染 Base URL
     */
    @GetMapping("/server-info")
    public HttpResponse serverInfo(HttpServletRequest request) {
        return HttpResponse.object(adminService.serverInfo(request));
    }
}
