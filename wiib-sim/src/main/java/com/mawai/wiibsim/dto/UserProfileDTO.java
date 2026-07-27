package com.mawai.wiibsim.dto;

import com.mawai.wiibcommon.dto.RankingDTO;
import lombok.Data;

import java.util.List;

/**
 * 排行榜点进来的用户详情：榜上那一行 + 当前持仓。交易历史是分页的，另走一个接口。
 * <p>
 * 【这个接口本身受隐私开关门控】目标用户关掉 profile_public 后，除本人外一律 403。
 */
@Data
public class UserProfileDTO {

    /** 榜单行原样复用（含名次/总资产/收益率/硬实力盈亏），口径与排行榜完全一致 */
    private RankingDTO summary;

    private List<ProfilePositionDTO> spotPositions;

    private List<ProfilePositionDTO> futuresPositions;
}
