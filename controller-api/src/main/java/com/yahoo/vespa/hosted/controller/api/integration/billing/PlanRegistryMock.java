package com.yahoo.vespa.hosted.controller.api.integration.billing;

import com.yahoo.config.provision.NodeResources;
import com.yahoo.vespa.hosted.controller.api.integration.resource.CostInfo;
import com.yahoo.vespa.hosted.controller.api.integration.resource.ResourceUsage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.stream.Stream;

public class PlanRegistryMock implements PlanRegistry {

    public static final Plan freeTrial = new MockPlan("trial", false, 0, 0, 0, 200, "Free Trial - for testing purposes");
    public static final Plan paidPlan  = new MockPlan("paid", true, "0.09", "0.009", "0.0003", 500, "Paid Plan - for testing purposes");
    public static final Plan nonePlan  = new MockPlan("none", false, 0, 0, 0, 0, "None Plan - for testing purposes");

    @Override
    public Plan defaultPlan() {
        return freeTrial;
    }

    @Override
    public Optional<Plan> plan(PlanId planId) {
        return Stream.of(freeTrial, paidPlan, nonePlan)
                .filter(p -> p.id().equals(planId))
                .findAny();
    }

    private static class MockPlan implements Plan {
        private final PlanId planId;
        private final String description;
        private final CostCalculator costCalculator;
        private final QuotaCalculator quotaCalculator;
        private final boolean billed;

        public MockPlan(String planId, boolean billed, double cpuPrice, double memPrice, double dgbPrice, int quota, String description) {
            this(PlanId.from(planId), billed, new MockCostCalculator(cpuPrice, memPrice, dgbPrice), () -> Quota.unlimited().withBudget(quota), description);
        }

        public MockPlan(String planId, boolean billed, String cpuPrice, String memPrice, String dgbPrice, int quota, String description) {
            this(PlanId.from(planId), billed, new MockCostCalculator(cpuPrice, memPrice, dgbPrice), () -> Quota.unlimited().withBudget(quota), description);
        }

        public MockPlan(PlanId planId, boolean billed, MockCostCalculator calculator, QuotaCalculator quota, String description) {
            this.planId = planId;
            this.billed = billed;
            this.costCalculator = calculator;
            this.quotaCalculator = quota;
            this.description = description;
        }

        @Override
        public PlanId id() {
            return planId;
        }

        @Override
        public String displayName() {
            return description;
        }

        @Override
        public CostCalculator calculator() {
            return costCalculator;
        }

        @Override
        public QuotaCalculator quota() {
            return quotaCalculator;
        }

        @Override
        public boolean isBilled() {
            return billed;
        }
    }

    private static class MockCostCalculator implements CostCalculator {
        private static final BigDecimal millisPerHour = BigDecimal.valueOf(60 * 60 * 1000);
        private final BigDecimal cpuHourCost;
        private final BigDecimal memHourCost;
        private final BigDecimal dgbHourCost;

        public MockCostCalculator(String cpuPrice, String memPrice, String dgbPrice) {
            this(new BigDecimal(cpuPrice), new BigDecimal(memPrice), new BigDecimal(dgbPrice));
        }

        public MockCostCalculator(double cpuPrice, double memPrice, double dgbPrice) {
            this(BigDecimal.valueOf(cpuPrice), BigDecimal.valueOf(memPrice), BigDecimal.valueOf(dgbPrice));
        }

        public MockCostCalculator(BigDecimal cpuPrice, BigDecimal memPrice, BigDecimal dgbPrice) {
            this.cpuHourCost = cpuPrice;
            this.memHourCost = memPrice;
            this.dgbHourCost = dgbPrice;
        }

        @Override
        public CostInfo calculate(ResourceUsage usage) {
            var cpuCost = usage.getCpuMillis().multiply(cpuHourCost).divide(millisPerHour, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
            var memCost = usage.getMemoryMillis().multiply(memHourCost).divide(millisPerHour, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
            var dgbCost = usage.getDiskMillis().multiply(dgbHourCost).divide(millisPerHour, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);

            return new CostInfo(
                    usage.getApplicationId(),
                    usage.getZoneId(),
                    usage.getCpuMillis().divide(millisPerHour, RoundingMode.HALF_UP),
                    usage.getMemoryMillis().divide(millisPerHour, RoundingMode.HALF_UP),
                    usage.getDiskMillis().divide(millisPerHour, RoundingMode.HALF_UP),
                    cpuCost,
                    memCost,
                    dgbCost
            );
        }

        @Override
        public double calculate(NodeResources resources) {
            return resources.cost();
        }
    }
}
