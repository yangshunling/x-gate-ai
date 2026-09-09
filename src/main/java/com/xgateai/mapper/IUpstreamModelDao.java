package com.xgateai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xgateai.entity.UpstreamModel;
import org.apache.ibatis.annotations.Mapper;

/**
 * IUpstreamModelDao 渠道模型数据访问接口
 * <p>
 * 基于 MyBatis-Plus BaseMapper 扩展，提供 x_gate_model 表的通用 CRUD 操作。
 * 路由候选列表查询（按 fail_count 升序）由策略类直接调用此接口。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/9
 */
@Mapper
public interface IUpstreamModelDao extends BaseMapper<UpstreamModel> {
}
