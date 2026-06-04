package com.insurance.uw.application.service.handler;

import com.insurance.uw.common.enums.CalcType;
import com.insurance.uw.domain.model.entity.FeatureConfig;
import com.insurance.uw.application.feature.handler.CompositeCalcHandler;
import com.insurance.uw.application.feature.handler.DatabaseQueryCalcHandler;
import com.insurance.uw.application.feature.handler.ExpressionCalcHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Stub handlers - 桩处理器")
class StubHandlerTest {

    @Nested
    @DisplayName("ExpressionCalcHandler")
    class ExpressionHandler {

        @Test
        @DisplayName("getSupportedType → 返回 EXPRESSION")
        void supportedType() {
            assertThat(new ExpressionCalcHandler().getSupportedType()).isEqualTo(CalcType.EXPRESSION);
        }

        @Test
        @DisplayName("execute → 抛出 UnsupportedOperationException")
        void executeThrows() {
            assertThatThrownBy(() -> new ExpressionCalcHandler().execute(null, new FeatureConfig()))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("暂未实现");
        }
    }

    @Nested
    @DisplayName("DatabaseQueryCalcHandler")
    class DatabaseQueryHandler {

        @Test
        @DisplayName("getSupportedType → 返回 DATABASE_QUERY")
        void supportedType() {
            assertThat(new DatabaseQueryCalcHandler().getSupportedType()).isEqualTo(CalcType.DATABASE_QUERY);
        }

        @Test
        @DisplayName("execute → 抛出 UnsupportedOperationException")
        void executeThrows() {
            assertThatThrownBy(() -> new DatabaseQueryCalcHandler().execute(null, new FeatureConfig()))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("暂未实现");
        }
    }

    @Nested
    @DisplayName("CompositeCalcHandler")
    class CompositeHandler {

        @Test
        @DisplayName("getSupportedType → 返回 COMPOSITE")
        void supportedType() {
            assertThat(new CompositeCalcHandler().getSupportedType()).isEqualTo(CalcType.COMPOSITE);
        }

        @Test
        @DisplayName("execute → 抛出 UnsupportedOperationException")
        void executeThrows() {
            assertThatThrownBy(() -> new CompositeCalcHandler().execute(null, new FeatureConfig()))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("暂未实现");
        }
    }
}
