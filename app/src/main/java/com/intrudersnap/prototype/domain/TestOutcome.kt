package com.intrudersnap.prototype.domain

/**
 * Outcome of a feasibility / acceptance test.
 *
 * PASS is only ever assigned from recorded evidence. NOT_TESTED is the default.
 * BLOCKED means Android (or the OEM ROM) refused the operation.
 * INCONCLUSIVE means the test ran but did not produce a definitive answer (e.g. timeout).
 */
enum class TestOutcome(val label: String) {
    PASS("PASS"),
    FAIL("FAIL"),
    BLOCKED("BLOCKED"),
    INCONCLUSIVE("INCONCLUSIVE"),
    NOT_TESTED("NOT TESTED");

    companion object {
        fun fromName(name: String?): TestOutcome =
            entries.firstOrNull { it.name == name } ?: NOT_TESTED
    }
}

/** Dashboard status labels required by the specification (section 7). */
enum class StatusLabel(val label: String) {
    SUPPORTED("Supported"),
    PERMISSION_REQUIRED("Permission required"),
    NOT_YET_TESTED("Not yet tested"),
    UNSUPPORTED("Unsupported"),
    TEST_FAILED("Test failed"),
}
