package io.warpnect.debug.directshell;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MicrophonePolicyApiTest {
    public static final class ModernCriterion {
        private final int rule;
        ModernCriterion(int rule) { this.rule = rule; }
        public int getRule() { return rule; }
    }

    public static final class LegacyCriterion {
        private final int mRule;
        LegacyCriterion(int rule) { mRule = rule; }
    }

    public static final class Rules {
        private final java.util.List<?> criteria;
        Rules(Object... criteria) { this.criteria = java.util.Arrays.asList(criteria); }
        java.util.List<?> getCriteria() { return criteria; }
    }

    @Test public void belowMinimumIsUnavailableNotPermissionDenied() {
        assertEquals(MicrophonePolicyApi.Adapter.UNSUPPORTED, MicrophonePolicyApi.adapterForSdk(25));
        assertEquals(MicrophonePolicyApi.Adapter.UNSUPPORTED, MicrophonePolicyApi.adapterForSdk(-1));
    }

    @Test public void legacyFrameworkDoesNotRequireAttributionSource() {
        for (int sdk = 26; sdk <= 30; sdk++) {
            assertEquals(MicrophonePolicyApi.Adapter.LEGACY_PACKAGE, MicrophonePolicyApi.adapterForSdk(sdk));
        }
    }

    @Test public void modernFrameworkRetainsAttributedContext() {
        for (int sdk : new int[] {31, 33, 36}) {
            assertEquals(MicrophonePolicyApi.Adapter.ATTRIBUTED, MicrophonePolicyApi.adapterForSdk(sdk));
        }
    }

    @Test public void capturePresetIsTheOnlyPermittedGlobalCriterion() {
        assertTrue(MicrophonePolicyApi.isCapturePresetCriterion(2));
        for (int rule : new int[] {0, 1, 4, 8, 0x8002, 0x8004}) {
            assertFalse(MicrophonePolicyApi.isCapturePresetCriterion(rule));
        }
    }

    @Test public void unmappedLegacyPortRequiresExactFrameworkSubmixIdentity() {
        assertEquals(25, MicrophonePolicyApi.normalizedType(0, 0x80000100, 0x80000100));
        assertEquals(0, MicrophonePolicyApi.normalizedType(0, 0x80000004, 0x80000100));
        assertEquals(0, MicrophonePolicyApi.normalizedType(0, 0, 0));
        assertEquals(15, MicrophonePolicyApi.normalizedType(15, 0x80000100, 0x80000100));
    }

    @Test public void auditsRealCriterionGetterAndLegacyFieldShape() throws Exception {
        assertTrue(MicrophonePolicyApi.auditCaptureRules(new Rules(new ModernCriterion(2)), 1));
        assertTrue(MicrophonePolicyApi.auditCaptureRules(new Rules(new LegacyCriterion(2)), 1));
    }

    @Test public void uidOrExclusionCannotPassEitherFrameworkAdapter() throws Exception {
        assertFalse(MicrophonePolicyApi.auditCaptureRules(new Rules(new ModernCriterion(4)), 1));
        assertFalse(MicrophonePolicyApi.auditCaptureRules(new Rules(new LegacyCriterion(0x8002)), 1));
    }

    @Test public void missingOrExtraRulesCannotFabricateGlobalSupport() throws Exception {
        assertFalse(MicrophonePolicyApi.auditCaptureRules(new Rules(), 0));
        assertFalse(MicrophonePolicyApi.auditCaptureRules(new Rules(new LegacyCriterion(2)), 3));
    }

    @Test(expected = NoSuchFieldException.class) public void unknownCriterionIsNotAssumedToBeCapture() throws Exception {
        MicrophonePolicyApi.auditCaptureRules(new Rules(new Object()), 1);
    }
}
