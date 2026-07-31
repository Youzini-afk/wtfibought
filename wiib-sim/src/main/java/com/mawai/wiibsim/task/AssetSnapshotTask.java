package com.mawai.wiibsim.task;

import com.mawai.wiibsim.service.AssetSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssetSnapshotTask {

    private final AssetSnapshotService assetSnapshotService;

    @Scheduled(cron = "0 0 0 * * *")
    public void dailySnapshot() {
        Thread.startVirtualThread(() -> {
            log.info("开始每日资产快照(收尾昨日)");
            assetSnapshotService.snapshotAll();
        });
    }

    /** 五分钟点只保留 31 天；清理与每日全量快照错峰执行。 */
    @Scheduled(cron = "0 30 3 * * *")
    public void purgeIntradayHistory() {
        Thread.startVirtualThread(assetSnapshotService::purgeIntradayHistory);
    }
}
