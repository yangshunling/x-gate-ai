package com.xgateai.application.controller;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xgateai.adminbridge.service.AdminService;
import com.xgateai.application.entity.CallLog;
import com.xgateai.application.entity.User;
import com.xgateai.application.model.dto.ApiKeyCreateDTO;
import com.xgateai.application.model.dto.ChangePasswordDTO;
import com.xgateai.application.model.dto.ChannelDTO;
import com.xgateai.application.model.dto.LoginDTO;
import com.xgateai.application.model.dto.ProviderDTO;
import com.xgateai.application.model.response.HttpResponse;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
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
 * 登录态校验由 AdminAuthInterceptor 统一拦截（/admin/login 除外）
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
     * 管理员登录：校验用户名密码，成功后会话写入登录态
     *
     * @param loginDTO 登录参数（用户名、密码）
     * @param session  会话
     * @return 登录用户信息（含 id/username）
     */
    @PostMapping("/login")
    public HttpResponse login(@RequestBody @Valid LoginDTO loginDTO, HttpSession session) {
        return HttpResponse.object(adminService.login(loginDTO.getUsername(), loginDTO.getPassword(), session));
    }

    /**
     * 管理员登出：销毁会话
     *
     * @param session 会话
     * @return 登出成功
     */
    @PostMapping("/logout")
    public HttpResponse logout(HttpSession session) {
        adminService.logout(session);
        return HttpResponse.successForMessage("登出成功");
    }

    /**
     * 查询当前登录用户信息（未登录抛 CommonException 由全局处理器处理）
     *
     * @param session 会话
     * @return 当前登录用户 {id, username}
     */
    @GetMapping("/session")
    public HttpResponse session(HttpSession session) {
        User user = adminService.currentUser(session);
        JSONObject result = new JSONObject();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        return HttpResponse.object(result);
    }

    /**
     * 修改当前登录用户密码
     *
     * @param dto     修改密码参数
     * @param session 会话
     * @return 密码修改成功
     */
    @PostMapping("/change-password")
    public HttpResponse changePassword(@RequestBody @Valid ChangePasswordDTO dto, HttpSession session) {
        adminService.changePassword(dto, session);
        return HttpResponse.successForMessage("密码修改成功");
    }

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
     * 查询全部启用状态的上游 Provider（供通道绑定时下拉）
     *
     * @return 启用的 Provider 列表
     */
    @GetMapping("/providers/enabled")
    public HttpResponse enabledProviders() {
        return HttpResponse.object(adminService.listEnabledProviders());
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
     * 查询全部对外模型通道列表（含绑定上游信息与顺序）
     *
     * @return 通道列表
     */
    @GetMapping("/channels")
    public HttpResponse channels() {
        return HttpResponse.object(adminService.listChannels());
    }

    /**
     * 新增对外模型通道
     *
     * @param dto 通道参数
     * @return 保存成功
     */
    @PostMapping("/channel")
    public HttpResponse saveChannel(@RequestBody @Valid ChannelDTO dto) {
        adminService.saveChannel(dto);
        return HttpResponse.objectForMessage(null, "保存成功");
    }

    /**
     * 更新对外模型通道
     *
     * @param dto 通道参数
     * @return 更新成功
     */
    @PutMapping("/channel")
    public HttpResponse updateChannel(@RequestBody @Valid ChannelDTO dto) {
        adminService.saveChannel(dto);
        return HttpResponse.objectForMessage(null, "更新成功");
    }

    /**
     * 删除对外模型通道
     *
     * @param id 通道 ID
     * @return 删除成功
     */
    @DeleteMapping("/channel/{id}")
    public HttpResponse deleteChannel(@PathVariable Long id) {
        adminService.deleteChannel(id);
        return HttpResponse.successForMessage("删除成功");
    }

    /**
     * 查询全部对外调用 API Key 列表（key 明文脱敏展示）
     *
     * @return API Key 列表
     */
    @GetMapping("/keys")
    public HttpResponse keys() {
        return HttpResponse.object(adminService.listApiKeys());
    }

    /**
     * 创建对外调用 API Key，result 携带完整明文 key（一次性展示）
     *
     * @param dto     创建参数
     * @param session 会话
     * @return {"key": 完整明文, "name": 名称}
     */
    @PostMapping("/key")
    public HttpResponse createKey(@RequestBody @Valid ApiKeyCreateDTO dto, HttpSession session) {
        String key = adminService.createApiKey(dto, session);
        JSONObject result = new JSONObject();
        result.put("key", key);
        result.put("name", dto.getName());
        return HttpResponse.object(result);
    }

    /**
     * 启用/停用切换对外调用 API Key
     *
     * @param id API Key ID
     * @return 操作成功
     */
    @PutMapping("/key/{id}/toggle")
    public HttpResponse toggleKey(@PathVariable Long id) {
        adminService.toggleApiKey(id);
        return HttpResponse.objectForMessage(null, "操作成功");
    }

    /**
     * 删除对外调用 API Key
     *
     * @param id API Key ID
     * @return 删除成功
     */
    @DeleteMapping("/key/{id}")
    public HttpResponse deleteKey(@PathVariable Long id) {
        adminService.deleteApiKey(id);
        return HttpResponse.successForMessage("删除成功");
    }

    /**
     * 分页查询调用日志，可按对外模型名 / API Key 精确过滤
     *
     * @param publicModel 对外模型名（可选）
     * @param apiKeyName  API Key（可选）
     * @param pageNum     页码，默认 1
     * @param pageSize    每页条数，默认 20
     * @return 调用日志分页结果
     */
    @GetMapping("/logs")
    public HttpResponse logs(@RequestParam(required = false) String publicModel,
                             @RequestParam(required = false) String apiKeyName,
                             @RequestParam(defaultValue = "1") int pageNum,
                             @RequestParam(defaultValue = "20") int pageSize) {
        Page<CallLog> page = adminService.queryLogs(publicModel, apiKeyName, pageNum, pageSize);
        return HttpResponse.list(page.getRecords(), page.getTotal(), pageNum, pageSize);
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
}
