/*
 * Copyright 2024-2026 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.hyprmxadapter

import android.content.Context
import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.CUSTOM
import com.hyprmx.android.sdk.consent.ConsentStatus
import com.hyprmx.android.sdk.core.HyprMX
import com.hyprmx.android.sdk.utility.HyprMXLog
import com.hyprmx.android.sdk.utility.HyprMXProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch

object HyprMXAdapterConfiguration : PartnerAdapterConfiguration {
    /**
     * The partner name for internal uses.
     */
    override val partnerId = "hyprmx"

    /**
     * The partner name for external uses.
     */
    override val partnerDisplayName = "HyprMX"

    /**
     * The partner SDK version.
     */
    override val partnerSdkVersion = HyprMXProperties.version

    /**
     * The partner adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion = BuildConfig.CHARTBOOST_MEDIATION_HYPRMX_ADAPTER_VERSION

    /**
     * Whether or not HyprMX debug logging is enabled.
     */
    var isDebugLoggingEnabled = false
        set(value) {
            field = value
            HyprMXLog.enableDebugLogs(value)
            PartnerLogController.log(
                CUSTOM,
                "HyprMX debug logging is ${if (value) "enabled" else "disabled"}.",
            )
        }

    /**
     * Use to manually set the consent status on the HyprMX SDK.
     * This is generally unnecessary as the Mediation SDK will set the consent status automatically based on the latest consent info.
     *
     * @param context The Android Context.
     * @param status The HyprMX ConsentStatus.
     */
    fun setConsentStatusOverride(context: Context, status: ConsentStatus) {
        isConsentStatusOverridden = true
        HyprMXAdapter.setUserConsentTask(context, status)
        PartnerLogController.log(CUSTOM, "HyprMX consent status overridden to $status")
    }

    /**
     * Whether consent status has been manually overridden by the publisher.
     */
    internal var isConsentStatusOverridden = false
}
