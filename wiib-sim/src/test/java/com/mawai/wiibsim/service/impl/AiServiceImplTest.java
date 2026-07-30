package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.constant.AiFunctions;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.mapper.AiModelAssignmentMapper;
import com.mawai.wiibcommon.mapper.AiRuntimeConfigMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiServiceImplTest {

    @Test
    void disabledAssignmentStopsBeforeConfigOrNetworkLookup() {
        AiRuntimeConfigMapper configMapper = mock(AiRuntimeConfigMapper.class);
        AiModelAssignmentMapper assignmentMapper = mock(AiModelAssignmentMapper.class);
        AiModelAssignment assignment = new AiModelAssignment();
        assignment.setFunctionName(AiFunctions.BSTOCK_ALIAS);
        assignment.setConfigId(42L);
        assignment.setEnabled(false);
        when(assignmentMapper.selectByFunction(AiFunctions.BSTOCK_ALIAS)).thenReturn(assignment);
        AiServiceImpl service = new AiServiceImpl(configMapper, assignmentMapper);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.chatFor(AiFunctions.BSTOCK_ALIAS, "prompt", null));

        assertTrue(error.getMessage().contains("AI功能已关闭"));
        verify(configMapper, never()).selectById(42L);
    }
}
