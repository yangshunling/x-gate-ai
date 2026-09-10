package com.xgateai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xgateai.entity.ModelChannel;
import org.apache.ibatis.annotations.Mapper;

/**
 * IModelChannelDao 模型通道数据访问接口
 * <p>
 * 基于 MyBatis-Plus BaseMapper 扩展，提供 customer 表的通用 CRUD 操作。
 * API Key 鉴权查询（精确匹配单条启用通道）在此层完成。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Mapper
public interface IModelChannelDao extends BaseMapper<ModelChannel> {
}
