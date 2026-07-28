package com.mawai.wiibquant.agent.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRegistryTest {

    private final ApprovalRegistry registry = new ApprovalRegistry();

    @Test
    void pendingIsDrainedOnce() {
        registry.requestApproval("s1", "BTCUSDT", "expensive");

        assertThat(registry.drainPending("s1")).isPresent();
        assertThat(registry.drainPending("s1")).isEmpty(); // drain 后清空
    }

    @Test
    void approvalIsConsumedOnce() {
        registry.approve("s1");

        assertThat(registry.consumeApproval("s1")).isTrue();
        assertThat(registry.consumeApproval("s1")).isFalse(); // 一次授权只放行一次
    }

    /** hasApproval 是路由侧的"只探不消费"：探完授权还在，工具闸门照常消费 */
    @Test
    void peekDoesNotConsumeApproval() {
        assertThat(registry.hasApproval("s1")).isFalse(); // 未授权时探不到

        registry.approve("s1");

        assertThat(registry.hasApproval("s1")).isTrue();
        assertThat(registry.hasApproval("s1")).isTrue();      // 反复探不消费
        assertThat(registry.consumeApproval("s1")).isTrue();  // 消费仍归工具闸门
        assertThat(registry.hasApproval("s1")).isFalse();     // 消费后探不到
    }

    @Test
    void rejectClearsApprovalAndPending() {
        registry.approve("s1");
        registry.requestApproval("s1", "BTCUSDT", "expensive");

        registry.reject("s1");

        assertThat(registry.consumeApproval("s1")).isFalse();
        assertThat(registry.drainPending("s1")).isEmpty();
    }

    @Test
    void activeSessionSlotFallback() {
        registry.markActive("wb-1-abc");

        assertThat(registry.activeSession()).isEqualTo("wb-1-abc");
    }
}
