package com.metrolist.android.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyInstallMigrationTest {
    @Test
    fun recognizesEverySupportedKmpIdentity() {
        listOf(
            "ab917eed5930e2e178a42ef5b3d591f2",
            "1bb9fdc7307b0d971f558159b1b4babd",
            "13e578bb5e23ac684507aa55c5dffbb8",
            "f49560b8795dcd167abbda9b6648e4c1",
            "ddc8cfa71888f44e5c60070b2c0a6d95",
            "3c6be733ec5070e0c77075f266e55cfe",
            "f9dcf29fe3bf6a19566c256ed40f3ab2",
            "e7e4f07ce4641e2a1c5ffc29f13c2b44",
            "7b8f98cc39e22247f55efd73850c79cc",
            "fe6500ef7caaa9f120a7eb055e41e43f",
            "e5f6373e45223baefabe1bd7711c90c4",
            "6d6fbb8ee43f3a034805108cff296cb0",
            "c22dd17615df8590dbde079d295d435b",
            "aaf3870d956fad2ee273a66334655711",
        ).forEach { assertTrue(LegacyInstallMigration.isKmpIdentity(it)) }
        assertFalse(LegacyInstallMigration.isKmpIdentity("legacy-v13"))
    }
}
