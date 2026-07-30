package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.BStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BStockMapper extends BaseMapper<BStock> {

    /** 与买卖事务共用的目录行锁，防止管理员暂停与订单成交穿插。 */
    @Select("SELECT * FROM bstock WHERE symbol = #{symbol} FOR UPDATE")
    BStock selectBySymbolForUpdate(@Param("symbol") String symbol);

    /** 管理端改变生命周期时锁住同一行，与交易事务形成明确先后顺序。 */
    @Select("SELECT * FROM bstock WHERE id = #{id} FOR UPDATE")
    BStock selectByIdForUpdate(@Param("id") Long id);
}
