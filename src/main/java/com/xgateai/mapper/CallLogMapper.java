package com.xgateai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xgateai.application.entity.CallLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * CallLogMapper 调用日志 Mapper
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Mapper
public interface CallLogMapper extends BaseMapper<CallLog> {
}
