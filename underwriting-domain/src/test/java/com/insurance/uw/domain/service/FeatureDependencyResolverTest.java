package com.insurance.uw.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FeatureDependencyResolver - 特征依赖拓扑排序")
class FeatureDependencyResolverTest {

    private FeatureDependencyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new FeatureDependencyResolver();
    }

    private static FeatureConfig fc(String code, String... deps) {
        FeatureConfig cfg = new FeatureConfig();
        cfg.setFeatureCode(code);
        cfg.setDependsOn(deps.length > 0 ? Arrays.asList(deps) : null);
        return cfg;
    }

    @Nested
    @DisplayName("基础场景")
    class BasicScenarios {

        @Test
        @DisplayName("空特征集合 → 返回空分层")
        void emptyFeatureSet() {
            List<Set<String>> layers = resolver.topoSort(Set.of(), Map.of());
            assertThat(layers).isEmpty();
        }

        @Test
        @DisplayName("单个无依赖特征 → 单层包含该特征")
        void singleFeatureNoDeps() {
            Map<String, FeatureConfig> configMap = Map.of("f1", fc("f1"));
            List<Set<String>> layers = resolver.topoSort(Set.of("f1"), configMap);

            assertThat(layers).hasSize(1);
            assertThat(layers.get(0)).containsExactly("f1");
        }

        @Test
        @DisplayName("两个独立特征 → 同一层并发执行")
        void twoIndependentFeatures() {
            Map<String, FeatureConfig> configMap = Map.of(
                    "f1", fc("f1"),
                    "f2", fc("f2")
            );
            List<Set<String>> layers = resolver.topoSort(Set.of("f1", "f2"), configMap);

            assertThat(layers).hasSize(1);
            assertThat(layers.get(0)).containsExactlyInAnyOrder("f1", "f2");
        }

        @Test
        @DisplayName("特征不在 configMap 中 → 视为无依赖")
        void featureNotInConfigMap() {
            List<Set<String>> layers = resolver.topoSort(Set.of("f1"), Map.of());
            assertThat(layers).hasSize(1);
            assertThat(layers.get(0)).containsExactly("f1");
        }

        @Test
        @DisplayName("特征 config 的 dependsOn 为 null → 视为无依赖")
        void nullDependsOn() {
            Map<String, FeatureConfig> configMap = Map.of("f1", fc("f1"));
            List<Set<String>> layers = resolver.topoSort(Set.of("f1"), configMap);

            assertThat(layers).hasSize(1);
            assertThat(layers.get(0)).containsExactly("f1");
        }

        @Test
        @DisplayName("特征 config 的 dependsOn 为空列表 → 视为无依赖")
        void emptyDependsOn() {
            Map<String, FeatureConfig> configMap = Map.of("f1", fc("f1"));
            List<Set<String>> layers = resolver.topoSort(Set.of("f1"), configMap);

            assertThat(layers).hasSize(1);
            assertThat(layers.get(0)).containsExactly("f1");
        }
    }

    @Nested
    @DisplayName("线性依赖链")
    class LinearChain {

        @Test
        @DisplayName("A → B → C 三层串行")
        void linearChain() {
            Map<String, FeatureConfig> configMap = Map.of(
                    "A", fc("A"),
                    "B", fc("B", "A"),
                    "C", fc("C", "B")
            );
            List<Set<String>> layers = resolver.topoSort(Set.of("A", "B", "C"), configMap);

            assertThat(layers).hasSize(3);
            assertThat(layers.get(0)).containsExactly("A");
            assertThat(layers.get(1)).containsExactly("B");
            assertThat(layers.get(2)).containsExactly("C");
        }
    }

    @Nested
    @DisplayName("钻石/菱形依赖")
    class DiamondDependency {

        @Test
        @DisplayName("A → B, A → C, B → D, C → D → 正确分层")
        void diamondDependency() {
            Map<String, FeatureConfig> configMap = Map.of(
                    "A", fc("A"),
                    "B", fc("B", "A"),
                    "C", fc("C", "A"),
                    "D", fc("D", "B", "C")
            );
            List<Set<String>> layers = resolver.topoSort(Set.of("A", "B", "C", "D"), configMap);

            assertThat(layers).hasSize(3);
            assertThat(layers.get(0)).containsExactly("A");
            assertThat(layers.get(1)).containsExactlyInAnyOrder("B", "C");
            assertThat(layers.get(2)).containsExactly("D");
        }
    }

    @Nested
    @DisplayName("复杂混合场景")
    class ComplexScenarios {

        @Test
        @DisplayName("混合依赖：无依赖 + 有依赖特征混合")
        void mixedDependencies() {
            Map<String, FeatureConfig> configMap = Map.of(
                    "A", fc("A"),
                    "B", fc("B"),
                    "C", fc("C", "A"),
                    "D", fc("D", "B"),
                    "E", fc("E", "C", "D")
            );
            List<Set<String>> layers = resolver.topoSort(Set.of("A", "B", "C", "D", "E"), configMap);

            assertThat(layers).hasSize(3);
            assertThat(layers.get(0)).containsExactlyInAnyOrder("A", "B");
            assertThat(layers.get(1)).containsExactlyInAnyOrder("C", "D");
            assertThat(layers.get(2)).containsExactly("E");
        }

        @Test
        @DisplayName("特征集是 configMap 的子集 → 只有传入的特征参与排序")
        void subsetOfConfigMap() {
            Map<String, FeatureConfig> configMap = Map.of(
                    "A", fc("A"),
                    "B", fc("B", "A"),
                    "C", fc("C", "B"),
                    "D", fc("D", "C")
            );
            List<Set<String>> layers = resolver.topoSort(Set.of("A", "B"), configMap);

            assertThat(layers).hasSize(2);
            assertThat(layers.get(0)).containsExactly("A");
            assertThat(layers.get(1)).containsExactly("B");
        }
    }

    @Nested
    @DisplayName("异常场景")
    class ExceptionScenarios {

        @Test
        @DisplayName("循环依赖 A→B→A → 抛出 IllegalStateException")
        void circularDependencyDetected() {
            Map<String, FeatureConfig> configMap = Map.of(
                    "A", fc("A", "B"),
                    "B", fc("B", "A")
            );
            assertThatThrownBy(() -> resolver.topoSort(Set.of("A", "B"), configMap))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("循环依赖");
        }

        @Test
        @DisplayName("三方循环依赖 A→B, B→C, C→A → 抛出异常")
        void threeWayCycle() {
            Map<String, FeatureConfig> configMap = Map.of(
                    "A", fc("A", "B"),
                    "B", fc("B", "C"),
                    "C", fc("C", "A")
            );
            assertThatThrownBy(() -> resolver.topoSort(Set.of("A", "B", "C"), configMap))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("循环依赖");
        }

        @Test
        @DisplayName("依赖不在特征集内 → 抛出异常")
        void missingDependency() {
            Map<String, FeatureConfig> configMap = Map.of(
                    "B", fc("B", "A")  // 依赖 A，但 A 不在 featureCodes 中
            );
            assertThatThrownBy(() -> resolver.topoSort(Set.of("B"), configMap))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("依赖")
                    .hasMessageContaining("A")
                    .hasMessageContaining("未包含");
        }

        @Test
        @DisplayName("自依赖特征 A→A → 循环依赖检测")
        void selfDependency() {
            Map<String, FeatureConfig> configMap = Map.of(
                    "A", fc("A", "A")
            );
            // 自依赖: A depends_on A → inDegree[A]=1, 永远不会入队 → 检测为循环依赖
            assertThatThrownBy(() -> resolver.topoSort(Set.of("A"), configMap))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("循环依赖");
        }
    }

    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("大量特征（100 个独立特征 → 全部在同一层）")
        void manyIndependentFeatures() {
            Set<String> codes = new LinkedHashSet<>();
            Map<String, FeatureConfig> configMap = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                String code = "f" + i;
                codes.add(code);
                configMap.put(code, fc(code));
            }
            List<Set<String>> layers = resolver.topoSort(codes, configMap);

            assertThat(layers).hasSize(1);
            assertThat(layers.get(0)).hasSize(100);
        }

        @Test
        @DisplayName("深链依赖（10 层链 → 每层一个特征）")
        void deepChain() {
            Set<String> codes = new LinkedHashSet<>();
            Map<String, FeatureConfig> configMap = new HashMap<>();
            String prev = null;
            for (int i = 0; i < 10; i++) {
                String code = "f" + i;
                codes.add(code);
                if (prev == null) {
                    configMap.put(code, fc(code));
                } else {
                    configMap.put(code, fc(code, prev));
                }
                prev = code;
            }
            List<Set<String>> layers = resolver.topoSort(codes, configMap);

            assertThat(layers).hasSize(10);
            for (int i = 0; i < 10; i++) {
                assertThat(layers.get(i)).hasSize(1);
                assertThat(layers.get(i)).contains("f" + i);
            }
        }

        @Test
        @DisplayName("多个特征依赖同一个前置特征 → 后续在同一层")
        void multipleDependOnSame() {
            Map<String, FeatureConfig> configMap = Map.of(
                    "A", fc("A"),
                    "B", fc("B", "A"),
                    "C", fc("C", "A"),
                    "D", fc("D", "A")
            );
            List<Set<String>> layers = resolver.topoSort(Set.of("A", "B", "C", "D"), configMap);

            assertThat(layers).hasSize(2);
            assertThat(layers.get(0)).containsExactly("A");
            assertThat(layers.get(1)).containsExactlyInAnyOrder("B", "C", "D");
        }
    }
}
