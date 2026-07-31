package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.AssetSnapshotDTO;
import com.mawai.wiibcommon.dto.AssetSeriesPointDTO;
import com.mawai.wiibcommon.dto.CategoryAveragesDTO;

import java.util.List;

public interface AssetSnapshotService {

    void snapshotAll();

    AssetSnapshotDTO getRealtimeSnapshot(Long userId);

    List<AssetSeriesPointDTO> getSeries(Long userId, String range, String interval);

    List<AssetSnapshotDTO> getHistory(Long userId, int days);

    CategoryAveragesDTO getCategoryAverages(Long userId, int days);

    void purgeIntradayHistory();

    /** 普通资产变动后的轻量失效；保留历史曲线，并允许当前采样桶由正确值覆盖。 */
    void invalidateRealtime(Long userId);

    void invalidateUser(Long userId);
}
