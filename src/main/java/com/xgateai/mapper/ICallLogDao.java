package com.xgateai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xgateai.entity.CallLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * ICallLogDao 调用日志数据访问接口
 * <p>
 * 基于 MyBatis-Plus BaseMapper 扩展，提供 call_log 表的通用 CRUD 操作。
 * 业务查询逻辑集中在 {@code CallLogAnalysisService} 中。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Mapper
public interface ICallLogDao extends BaseMapper<CallLog> {
}
