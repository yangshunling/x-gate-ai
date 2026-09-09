package com.xgateai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xgateai.entity.UpstreamProvider;
import org.apache.ibatis.annotations.Mapper;

/**
 * IUpstreamProviderDao 上游 Provider 数据访问接口
 * <p>
 * 基于 MyBatis-Plus BaseMapper 扩展，提供 upstream_providers 表的通用 CRUD 操作。
 * 路由候选列表查询（按 fail_count 升序）由策略类直接调用此接口。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Mapper
public interface IUpstreamProviderDao extends BaseMapper<UpstreamProvider> {
}
