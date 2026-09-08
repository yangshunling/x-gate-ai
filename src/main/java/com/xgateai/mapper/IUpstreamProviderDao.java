package com.xgateai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xgateai.entity.UpstreamProvider;
import org.apache.ibatis.annotations.Mapper;

/**
 * IUpstreamProviderDao 上游 Provider 数据访问接口
 * <p>
 * 定义上游大模型服务的 CRUD 操作，基于 MyBatis-Plus BaseMapper 扩展。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Mapper
public interface IUpstreamProviderDao extends BaseMapper<UpstreamProvider> {
}
