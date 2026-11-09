package com.ukkera.pos.android.independent.trainingcompositionv1

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ukkera.pos.security.AndroidTrainingSetupNamespaces
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.security.LocalSetupCompositionMode
import com.ukkera.pos.security.LocalTrainingCompositionSlot
import com.ukkera.pos.security.LocalTrainingSdkProfileV1
import com.ukkera.pos.types.DomainId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Source/compile-only T1/T2 extension of the existing genuine Android runtime-test harness.
 *
 * This class observes production Android training namespace/profile functions only. It creates
 * no native root, key, file, journal, service, SDK identity or device state and it must not be
 * reported as a native/device result. The class is compiled through `:apps:android-runtime-tests`
 * and is NOT RUN by the reviewed JVM compile/evidence run.
 */
@RunWith(AndroidJUnit4::class)
class TrainingCompositionNamespacesSourceTest {
    private val scope = BranchScope(
        DomainId("11111111-1111-4111-8111-111111111111"),
        DomainId("22222222-2222-4222-8222-222222222222"),
        DomainId("33333333-3333-4333-8333-333333333333"),
    )
    private val device = DomainId("44444444-4444-4444-8444-444444444444")

    @Test
    fun t1_t2_launch_and_employee_setup_namespaces_are_nonempty_and_distinct() {
        val first = LocalTrainingCompositionSlot.TRAINING_1
        val second = LocalTrainingCompositionSlot.TRAINING_2
        val firstLaunch = AndroidTrainingSetupNamespaces.launchDirectoryName(first)
        val secondLaunch = AndroidTrainingSetupNamespaces.launchDirectoryName(second)
        val firstIntent = AndroidTrainingSetupNamespaces.intentStorageSuffix(first)
        val secondIntent = AndroidTrainingSetupNamespaces.intentStorageSuffix(second)

        assertTrue(firstLaunch.isNotEmpty())
        assertTrue(secondLaunch.isNotEmpty())
        assertTrue(firstIntent.isNotEmpty())
        assertTrue(secondIntent.isNotEmpty())
        assertNotEquals(firstLaunch, secondLaunch)
        assertNotEquals(firstIntent, secondIntent)
    }

    @Test
    fun t1_t2_sdk_profiles_are_training_and_distinct_from_live_and_each_other() {
        val first = LocalTrainingSdkProfileV1(LocalTrainingCompositionSlot.TRAINING_1)
        val second = LocalTrainingSdkProfileV1(LocalTrainingCompositionSlot.TRAINING_2)
        val live = LocalMasterSdkProfileV1

        assertEquals(LocalSetupCompositionMode.TRAINING, first.mode)
        assertEquals(LocalSetupCompositionMode.TRAINING, second.mode)
        assertNotEquals(live.appId(scope), first.appId(scope))
        assertNotEquals(live.appId(scope), second.appId(scope))
        assertNotEquals(first.appId(scope), second.appId(scope))
        assertNotEquals(live.deviceName(device), first.deviceName(device))
        assertNotEquals(live.deviceName(device), second.deviceName(device))
        assertNotEquals(first.deviceName(device), second.deviceName(device))
    }
}
