package com.mawai.wiibsim.mapper;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAssetPointMapperSqlTest {

    @Test
    void bucketSamplingUsesOneJdbcBindingAndWindowRanking() {
        Configuration configuration = new Configuration();
        configuration.addMapper(UserAssetPointMapper.class);
        MappedStatement statement = configuration.getMappedStatement(
                UserAssetPointMapper.class.getName() + ".listBucketed");

        BoundSql boundSql = statement.getBoundSql(Map.of(
                "userId", 7L,
                "startMs", 1_800_000L,
                "bucketMs", 300_000L));

        long bucketBindings = boundSql.getParameterMappings().stream()
                .filter(mapping -> "bucketMs".equals(mapping.getProperty()))
                .count();
        String normalizedSql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertEquals(1, bucketBindings);
        assertEquals(3, boundSql.getParameterMappings().size());
        assertTrue(normalizedSql.contains("ROW_NUMBER() OVER"));
        assertFalse(normalizedSql.contains("DISTINCT ON"));
    }
}
