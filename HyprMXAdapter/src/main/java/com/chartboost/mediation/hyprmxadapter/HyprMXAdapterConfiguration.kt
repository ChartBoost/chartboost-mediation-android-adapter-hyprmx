package com.chartboost.mediation.hyprmxadapter

import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.hyprmx.android.sdk.utility.HyprMXLog
import com.hyprmx.android.sdk.utility.HyprMXProperties

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
     * Enable HyprMX debug logs.
     *
     * @param enabled True to enable debug logs, false otherwise.
     */
    fun enableDebugLogs(enabled: Boolean) {
        HyprMXLog.enableDebugLogs(enabled)
    }
}
