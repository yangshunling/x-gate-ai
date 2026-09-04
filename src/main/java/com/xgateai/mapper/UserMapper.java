package com.xgateai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xgateai.application.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * UserMapper 用户表 Mapper
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
