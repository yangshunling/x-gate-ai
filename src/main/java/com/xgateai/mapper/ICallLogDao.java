package com.xgateai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xgateai.entity.CallLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * ICallLogDao 调用日志数据访问接口
 * <p>
 * 定义调用日志的读写操作，基于 MyBatis-Plus BaseMapper 扩展。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Mapper
public interface ICallLogDao extends BaseMapper<CallLog> {
}
