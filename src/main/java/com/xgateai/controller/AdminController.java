package com.xgateai.controller;

import com.xgateai.dto.ChannelDTO;
import com.xgateai.dto.ConcurrencyLimitDTO;
import com.xgateai.dto.FetchModelsDTO;
import com.xgateai.dto.LogQueryDTO;
import com.xgateai.dto.ProviderDTO;
import com.xgateai.response.HttpResponse;
import com.xgateai.entity.CallLog;
import com.xgateai.service.admin.AdminManagementService;
import com.xgateai.service.log.CallLogAnalysisService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * AdminController 管理端控制器
 * <p>
 * 提供上游 Provider 和模型通道的 CRUD 接口，以及日志查询和仪表盘统计。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminManagementService adminManagementService;
    private final CallLogAnalysisService callLogAnalysisService;

    /**
     * 构造管理端控制器
     *
     * @param adminManagementService 管理端业务服务
     * @param callLogAnalysisService 调用日志统计分析服务
     */
    public AdminController(AdminManagementService adminManagementService,
                           CallLogAnalysisService callLogAnalysisService) {
        this.adminManagementService = adminManagementService;
        this.callLogAnalysisService = callLogAnalysisService;
    }

    // ==================== 上游渠道管理（含其下模型） ====================

    /**
     * 新增上游渠道
     *
     * @param dto 渠道请求参数
     * @return 保存成功响应
     */
    @PostMapping("/provider")
    public HttpResponse saveProvider(@RequestBody @Valid ProviderDTO dto) {
        adminManagementService.saveProvider(dto);
        return HttpResponse.successForMessage("保存成功");
    }

    /**
     * 更新指定上游渠道
     *
     * @param id  渠道 ID
     * @param dto 渠道请求参数（id 被路径参数覆盖）
     * @return 更新成功响应
     */
    @PutMapping("/provider/{id}")
    public HttpResponse updateProvider(@PathVariable Long id,
                                       @RequestBody @Valid ProviderDTO dto) {
        dto.setId(id);
        adminManagementService.saveProvider(dto);
        return HttpResponse.successForMessage("更新成功");
    }

    /**
     * 删除指定上游渠道
     *
     * @param id 渠道 ID
     * @return 删除成功响应
     */
    @DeleteMapping("/provider/{id}")
    public HttpResponse deleteProvider(@PathVariable Long id) {
        adminManagementService.deleteProvider(id);
        return HttpResponse.successForMessage("删除成功");
    }

    /**
     * 查询全部上游渠道列表（含其下模型与明文 API Key）
     *
     * @return 渠道列表响应
     */
    @GetMapping("/providers")
    public HttpResponse listProviders() {
        return HttpResponse.object(adminManagementService.listProviders());
    }

    /**
     * 测试指定渠道下全部模型的连通性
     *
     * @param id 渠道 ID
     * @return 各模型测试结果列表
     */
    @PostMapping("/provider/{id}/test")
    public HttpResponse testProvider(@PathVariable Long id) {
        return HttpResponse.object(adminManagementService.testProvider(id));
    }

    /**
     * 测试单个模型行的连通性
     *
     * @param id 模型行 ID
     * @return 该模型测试结果
     */
    @PostMapping("/model/{id}/test")
    public HttpResponse testModel(@PathVariable Long id) {
        return HttpResponse.object(adminManagementService.testModel(id));
    }

    /**
     * 探测上游渠道可用的模型列表（用于表单一键导入）
     *
     * @param dto 拉取模型参数（BaseUrl / API Key）
     * @return 含 usedStoredKey / models 字段的结果
     */
    @PostMapping("/provider/fetch-models")
    public HttpResponse fetchProviderModels(@RequestBody FetchModelsDTO dto) {
        return HttpResponse.object(adminManagementService.fetchProviderModels(dto));
    }

    /**
     * 批量测试全部渠道下全部模型的连通性
     *
     * @return 各模型测试结果列表
     */
    @PostMapping("/providers/test-all")
    public HttpResponse testAllProviders() {
        return HttpResponse.object(adminManagementService.testAllProviders());
    }

    // ==================== 客户/API KEY 管理 ====================

    /**
     * 新增客户（API Key）
     *
     * @param dto 客户请求参数
     * @return 含生成的 API Key 的保存成功响应
     */
    @PostMapping("/channel")
    public HttpResponse saveChannel(@RequestBody @Valid ChannelDTO dto) {
        String key = adminManagementService.saveChannel(dto);
        return HttpResponse.objectForMessage(key, "保存成功");
    }

    /**
     * 更新指定客户（API Key 不变）
     *
     * @param id  客户 ID
     * @param dto 客户请求参数（id 被路径参数覆盖）
     * @return 更新成功响应
     */
    @PutMapping("/channel/{id}")
    public HttpResponse updateChannel(@PathVariable Long id,
                                      @RequestBody @Valid ChannelDTO dto) {
        dto.setId(id);
        adminManagementService.saveChannel(dto);
        return HttpResponse.successForMessage("更新成功");
    }

    /**
     * 删除指定客户（API Key）
     *
     * @param id 客户 ID
     * @return 删除成功响应
     */
    @DeleteMapping("/channel/{id}")
    public HttpResponse deleteChannel(@PathVariable Long id) {
        adminManagementService.deleteChannel(id);
        return HttpResponse.successForMessage("删除成功");
    }

    /**
     * 查询全部客户列表
     *
     * @return 客户列表响应
     */
    @GetMapping("/channels")
    public HttpResponse listChannels() {
        return HttpResponse.object(adminManagementService.listChannels());
    }

    // ==================== 日志与看板 ====================

    /**
     * 分页查询调用日志
     *
     * @param query 日志查询过滤条件
     * @return 含列表/总数/分页信息的响应
     */
    @GetMapping("/logs")
    public HttpResponse queryLogs(@Valid LogQueryDTO query) {
        var page = callLogAnalysisService.queryLogs(query);
        return HttpResponse.list(page.getRecords(), page.getTotal(),
                page.getCurrent(), page.getSize());
    }

    /**
     * 按上游模型聚合统计（调用次数、Token、成功率、平均耗时）
     *
     * @return 统计结果列表
     */
    @GetMapping("/dashboard/models")
    public HttpResponse modelStats() {
        return HttpResponse.object(callLogAnalysisService.analyzeByModel());
    }

    /**
     * 按上游模型聚合失败次数统计
     *
     * @return 统计结果列表
     */
    @GetMapping("/dashboard/model-fail-stats")
    public HttpResponse modelFailStats() {
        return HttpResponse.object(callLogAnalysisService.analyzeFailuresByModel());
    }

    /**
     * 按上游渠道聚合调用权重统计
     *
     * @return 统计结果列表
     */
    @GetMapping("/dashboard/model-weight-stats")
    public HttpResponse modelWeightStats() {
        return HttpResponse.object(callLogAnalysisService.analyzeWeightByProvider());
    }

    /**
     * 今日客户调用 Top5 统计
     *
     * @return 统计结果列表
     */
    @GetMapping("/dashboard/customers")
    public HttpResponse customerStats() {
        return HttpResponse.object(callLogAnalysisService.topCustomersToday());
    }

    /**
     * 仪表盘汇总统计（总调用/今日调用/客户总数/资源数等）
     *
     * @return 汇总统计结果
     */
    @GetMapping("/dashboard")
    public HttpResponse dashboard() {
        return HttpResponse.object(callLogAnalysisService.dashboardStats());
    }

    /**
     * 获取服务器接入信息（IP、端口）
     *
     * @param port 端口，默认 8090
     * @return 服务器信息
     */
    @GetMapping("/server-info")
    public HttpResponse serverInfo(@RequestParam(defaultValue = "8090") int port) {
        return HttpResponse.object(adminManagementService.getServerInfo(port));
    }

    // ==================== 并发控制 ====================

    /**
     * 列出全部候选模型及并发状态
     *
     * @return 候选模型列表响应
     */
    @GetMapping("/concurrency/models")
    public HttpResponse listConcurrencyModels() {
        return HttpResponse.object(adminManagementService.listConcurrencyModels());
    }

    /**
     * 更新指定模型行的并发上限与失败次数
     *
     * @param id  模型行 ID
     * @param dto 流控参数
     * @return 更新成功响应
     */
    @PutMapping("/concurrency/model/{id}")
    public HttpResponse updateConcurrencyLimit(@PathVariable Long id,
                                               @RequestBody @Valid ConcurrencyLimitDTO dto) {
        adminManagementService.updateConcurrencyLimit(id, dto.getMaxConcurrency(), dto.getFailCount());
        return HttpResponse.successForMessage("流控参数已更新");
    }
}
