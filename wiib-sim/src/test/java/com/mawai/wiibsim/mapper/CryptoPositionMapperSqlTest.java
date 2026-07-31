package com.mawai.wiibsim.mapper;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoPositionMapperSqlTest {

    @Test
    void additionalBuyKeepsFrozenSharesInWeightedAverageCost() {
        Configuration configuration = new Configuration();
        configuration.addMapper(CryptoPositionMapper.class);
        MappedStatement statement = configuration.getMappedStatement(
                CryptoPositionMapper.class.getName() + ".upsertPosition");

        BoundSql boundSql = statement.getBoundSql(Map.of(
                "userId", 7L,
                "symbol", "AAPLBUSDT",
                "quantity", BigDecimal.ONE,
                "price", BigDecimal.TEN,
                "discount", BigDecimal.ZERO));
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertTrue(sql.contains("avg_cost * (crypto_position.quantity + COALESCE(crypto_position.frozen_quantity, 0))"));
        assertTrue(sql.contains("/ (crypto_position.quantity + COALESCE(crypto_position.frozen_quantity, 0) + ?)"));
    }
}
