package com.mawai.wiibsim.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 影子股票图标的 PostgreSQL 持久缓存。 */
@Data
@TableName("bstock_icon_cache")
public class BStockIconCache {

    @TableId(type = IdType.INPUT)
    private String symbol;
    private String sourceUrl;
    private String contentType;
    private byte[] imageData;
    private String contentHash;
    private Integer byteSize;
    private LocalDateTime cachedAt;
    private LocalDateTime updatedAt;
}
