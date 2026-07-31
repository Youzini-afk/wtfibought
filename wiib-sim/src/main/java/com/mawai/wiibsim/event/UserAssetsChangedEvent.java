package com.mawai.wiibsim.event;

/** 账户资金或持仓成功变动；监听方在事务提交后清理派生资产缓存。 */
public record UserAssetsChangedEvent(Long userId) {
}
