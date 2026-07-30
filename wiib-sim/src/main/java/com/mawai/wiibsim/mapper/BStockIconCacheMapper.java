package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibsim.entity.BStockIconCache;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BStockIconCacheMapper extends BaseMapper<BStockIconCache> {

    @Insert("""
            INSERT INTO bstock_icon_cache
                (symbol, source_url, content_type, image_data, content_hash, byte_size, cached_at, updated_at)
            VALUES
                (#{entry.symbol}, #{entry.sourceUrl}, #{entry.contentType}, #{entry.imageData},
                 #{entry.contentHash}, #{entry.byteSize}, #{entry.cachedAt}, #{entry.updatedAt})
            ON CONFLICT (symbol) DO UPDATE SET
                source_url = EXCLUDED.source_url,
                content_type = EXCLUDED.content_type,
                image_data = EXCLUDED.image_data,
                content_hash = EXCLUDED.content_hash,
                byte_size = EXCLUDED.byte_size,
                cached_at = EXCLUDED.cached_at,
                updated_at = EXCLUDED.updated_at
            """)
    int upsert(@Param("entry") BStockIconCache entry);
}
