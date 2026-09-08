package com.xgateai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xgateai.entity.ModelChannel;
import org.apache.ibatis.annotations.Mapper;

/**
 * IModelChannelDao 模型通道数据访问接口
 * <p>
 * 定义对外模型通道的 CRUD 操作，基于 MyBatis-Plus BaseMapper 扩展。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Mapper
public interface IModelChannelDao extends BaseMapper<ModelChannel> {
}
