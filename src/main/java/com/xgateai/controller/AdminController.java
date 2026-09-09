package com.xgateai.controller;

import com.xgateai.dto.ChannelDTO;
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

    public AdminController(AdminManagementService adminManagementService,
                           CallLogAnalysisService callLogAnalysisService) {
        this.adminManagementService = adminManagementService;
        this.callLogAnalysisService = callLogAnalysisService;
    }

    // ==================== 上游渠道管理（含其下模型） ====================

    @PostMapping("/provider")
    public HttpResponse saveProvider(@RequestBody @Valid ProviderDTO dto) {
        adminManagementService.saveProvider(dto);
        return HttpResponse.successForMessage("保存成功");
    }

    @PutMapping("/provider/{id}")
    public HttpResponse updateProvider(@PathVariable Long id,
                                       @RequestBody @Valid ProviderDTO dto) {
        dto.setId(id);
        adminManagementService.saveProvider(dto);
        return HttpResponse.successForMessage("更新成功");
    }

    @DeleteMapping("/provider/{id}")
    public HttpResponse deleteProvider(@PathVariable Long id) {
        adminManagementService.deleteProvider(id);
        return HttpResponse.successForMessage("删除成功");
    }

    @GetMapping("/providers")
    public HttpResponse listProviders() {
        return HttpResponse.object(adminManagementService.listProviders());
    }

    @PostMapping("/provider/{id}/test")
    public HttpResponse testProvider(@PathVariable Long id) {
        return HttpResponse.object(adminManagementService.testProvider(id));
    }

    @PostMapping("/model/{id}/test")
    public HttpResponse testModel(@PathVariable Long id) {
        return HttpResponse.object(adminManagementService.testModel(id));
    }

    @PostMapping("/provider/fetch-models")
    public HttpResponse fetchProviderModels(@RequestBody FetchModelsDTO dto) {
        return HttpResponse.object(adminManagementService.fetchProviderModels(dto));
    }

    @PostMapping("/providers/test-all")
    public HttpResponse testAllProviders() {
        return HttpResponse.object(adminManagementService.testAllProviders());
    }

    // ==================== 客户/对外 API Key 管理 ====================

    @PostMapping("/channel")
    public HttpResponse saveChannel(@RequestBody @Valid ChannelDTO dto) {
        String key = adminManagementService.saveChannel(dto);
        return HttpResponse.objectForMessage(key, "保存成功");
    }

    @PutMapping("/channel/{id}")
    public HttpResponse updateChannel(@PathVariable Long id,
                                      @RequestBody @Valid ChannelDTO dto) {
        dto.setId(id);
        adminManagementService.saveChannel(dto);
        return HttpResponse.successForMessage("更新成功");
    }

    @DeleteMapping("/channel/{id}")
    public HttpResponse deleteChannel(@PathVariable Long id) {
        adminManagementService.deleteChannel(id);
        return HttpResponse.successForMessage("删除成功");
    }

    @GetMapping("/channels")
    public HttpResponse listChannels() {
        return HttpResponse.object(adminManagementService.listChannels());
    }

    // ==================== 日志与看板 ====================

    @GetMapping("/logs")
    public HttpResponse queryLogs(@Valid LogQueryDTO query) {
        var page = callLogAnalysisService.queryLogs(query);
        return HttpResponse.list(page.getRecords(), page.getTotal(),
                page.getCurrent(), page.getSize());
    }

    @GetMapping("/dashboard/models")
    public HttpResponse modelStats() {
        return HttpResponse.object(callLogAnalysisService.analyzeByModel());
    }

    @GetMapping("/dashboard/model-fail-stats")
    public HttpResponse modelFailStats() {
        return HttpResponse.object(callLogAnalysisService.analyzeFailuresByModel());
    }

    @GetMapping("/dashboard/model-weight-stats")
    public HttpResponse modelWeightStats() {
        return HttpResponse.object(callLogAnalysisService.analyzeWeightByProvider());
    }

    @GetMapping("/dashboard/customers")
    public HttpResponse customerStats() {
        return HttpResponse.object(callLogAnalysisService.topCustomersToday());
    }

    @GetMapping("/dashboard")
    public HttpResponse dashboard() {
        return HttpResponse.object(callLogAnalysisService.dashboardStats());
    }

    @GetMapping("/server-info")
    public HttpResponse serverInfo(@RequestParam(defaultValue = "8090") int port) {
        return HttpResponse.object(adminManagementService.getServerInfo(port));
    }
}
