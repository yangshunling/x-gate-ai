package com.xgateai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xgateai.entity.UpstreamModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * IUpstreamModelDao 渠道模型数据访问接口
 * <p>
 * 基于 MyBatis-Plus BaseMapper 扩展，提供 upstream_model 表的通用 CRUD 操作。
 * 路由候选列表查询（按 fail_count 升序）由策略类直接调用此接口。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/9
 */
@Mapper
public interface IUpstreamModelDao extends BaseMapper<UpstreamModel> {

    /**
     * 原子自增指定模型行的 fail_count（数据库侧 fail_count = fail_count + 1，避免读改写覆盖）。
     *
     * @param id 模型行主键
     * @return 影响行数
     */
    @Update("UPDATE upstream_model SET fail_count = COALESCE(fail_count, 0) + 1 WHERE id = #{id}")
    int incrementFailCount(@Param("id") Long id);
}
