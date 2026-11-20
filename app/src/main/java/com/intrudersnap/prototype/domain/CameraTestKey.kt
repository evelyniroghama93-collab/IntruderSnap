package com.intrudersnap.prototype.domain

/** Camera feasibility investigation items A-G from the specification (section 9). */
enum class CameraTestKey(val code: String, val title: String) {
    A_PERMISSION("A", "Camera permission"),
    B_VISIBLE_CAMERAX("B", "CameraX while IntruderSnap is visibly open"),
    C_FGS_VISIBLE("C", "Camera through a declared foreground service"),
    D_FGS_REQUIREMENTS("D", "Android 14 foreground-service camera requirements"),
    E_BACKGROUND_AFTER_CALLBACK("E", "Camera service started from background after lock-screen callback"),
    F_PRIVACY_INDICATOR("F", "Camera privacy indicator behaviour"),
    G_DENIAL_BEHAVIOUR("G", "What happens when Android denies background camera access"),
}

data class TestResult(
    val outcome: TestOutcome = TestOutcome.NOT_TESTED,
    val detail: String = "",
    val timestampMillis: Long = 0L,
)
