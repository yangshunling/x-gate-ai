package com.xgateai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xgateai.application.entity.ApiKey;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * ApiKeyMapper 对外调用 API Key Mapper
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {
}
