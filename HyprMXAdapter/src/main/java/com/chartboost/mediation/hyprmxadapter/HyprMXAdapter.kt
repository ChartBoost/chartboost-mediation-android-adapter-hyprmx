/*
 * Copyright 2023-2024 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.hyprmxadapter

import android.content.Context
import android.util.Size
import com.chartboost.heliumsdk.HeliumSdk
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import com.hyprmx.android.sdk.banner.HyprMXBannerListener
import com.hyprmx.android.sdk.banner.HyprMXBannerSize
import com.hyprmx.android.sdk.banner.HyprMXBannerView
import com.hyprmx.android.sdk.consent.ConsentStatus
import com.hyprmx.android.sdk.core.HyprMX
import com.hyprmx.android.sdk.core.HyprMXErrors
import com.hyprmx.android.sdk.core.HyprMXIf
import com.hyprmx.android.sdk.core.HyprMXState
import com.hyprmx.android.sdk.placement.Placement
import com.hyprmx.android.sdk.placement.PlacementListener
import com.hyprmx.android.sdk.placement.RewardedPlacementListener
import com.hyprmx.android.sdk.utility.HyprMXLog
import com.hyprmx.android.sdk.utility.HyprMXProperties
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Chartboost Mediation HyperMX SDK adapter.
 */
class HyprMXAdapter : PartnerAdapter {
    companion object {
        /**
         * Enable HyprMX debug logs.
         * @param enabled True to enable debug logs, false otherwise.
         */
        fun enableDebugLogs(enabled: Boolean) {
            HyprMXLog.enableDebugLogs(enabled)
        }

        /**
         * Add a long debug log to HyprMX's logger for debugging.
         * @param tag a constant tag
         * @param message the log message
         */
        fun longDebugLog(
            tag: String,
            message: String,
        ) {
            HyprMXLog.longDebugLog(tag, message)
        }

        /**
         * Key for parsing the HyperMX SDK distributor ID.
         */
        private const val DISTRIBUTOR_ID_KEY = "distributor_id"

        /**
         * HyprMX shared preference key.
         */
        private const val HYPRMX_PREFS_KEY = "hyprmx_prefs"

        /**
         * HyprMX user consent key.
         */
        private const val HYPRMX_USER_CONSENT_KEY = "hyprmx_user_consent"

        /**
         * HyprMX gamer id key.
         */
        private const val HYPRMX_GAMER_ID_KEY = "hyprmx_gamer_id"

        /**
         * Convert a given HyprMX error code into a [ChartboostMediationError].
         *
         * @param error The HyprMX error code as an [Int].
         *
         * @return The corresponding [ChartboostMediationError].
         */
        internal fun getChartboostMediationError(error: HyprMXErrors) =
            when (error) {
                HyprMXErrors.NO_FILL -> ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL
                HyprMXErrors.SDK_NOT_INITIALIZED -> ChartboostMediationError.CM_INITIALIZATION_FAILURE_UNKNOWN
                HyprMXErrors.INVALID_BANNER_PLACEMENT_NAME, HyprMXErrors.PLACEMENT_DOES_NOT_EXIST,
                HyprMXErrors.PLACEMENT_NAME_NOT_SET,
                -> ChartboostMediationError.CM_LOAD_FAILURE_INVALID_PARTNER_PLACEMENT
                HyprMXErrors.DISPLAY_ERROR, HyprMXErrors.AD_FAILED_TO_RENDER -> ChartboostMediationError.CM_SHOW_FAILURE_MEDIA_BROKEN
                HyprMXErrors.AD_SIZE_NOT_SET -> ChartboostMediationError.CM_LOAD_FAILURE_INVALID_BANNER_SIZE
                else -> ChartboostMediationError.CM_PARTNER_ERROR
            }

        /**
         * A lambda to call for successful HyprMX ad shows.
         */
        internal var onShowSuccess: () -> Unit = {}

        /**
         * A lambda to call for failed HyprMX ad shows.
         */
        internal var onShowError: (hyprMXErrors: HyprMXErrors) -> Unit = { _: HyprMXErrors -> }
    }

    /**
     * Get the HyprMX SDK version.
     */
    override val partnerSdkVersion: String
        get() = HyprMXProperties.version

    /**
     * Get the HyprMX adapter version.
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
    override val adapterVersion: String
        get() = BuildConfig.CHARTBOOST_MEDIATION_HYPRMX_ADAPTER_VERSION

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "hyprmx"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "HyprMX"

    /**
     * Initialize the HyprMX SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize HyprMX.
     *
     * @return Result.success(Unit) if HyprMX successfully initialized, Result.failure(Exception) otherwise.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration,
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)

        return suspendCoroutine { continuation ->
            Json.decodeFromJsonElement<String>(
                (partnerConfiguration.credentials as JsonObject).getValue(DISTRIBUTOR_ID_KEY),
            ).trim()
                .takeIf { it.isNotEmpty() }?.let { distributorId ->
                    HyprMX.initialize(
                        context = context,
                        distributorId = distributorId,
                        userId = getGamerId(context),
                        consentStatus = getUserConsent(context),
                        ageRestrictedUser = true,
                        listener =
                            object : HyprMXIf.HyprMXInitializationListener {
                                override fun initializationComplete() {
                                    continuation.resume(
                                        Result.success(
                                            PartnerLogController.log(SETUP_SUCCEEDED),
                                        ),
                                    )
                                }

                                override fun initializationFailed() {
                                    PartnerLogController.log(SETUP_FAILED)
                                    continuation.resume(
                                        Result.failure(
                                            ChartboostMediationAdException(
                                                ChartboostMediationError.CM_INITIALIZATION_FAILURE_UNKNOWN,
                                            ),
                                        ),
                                    )
                                }
                            },
                    )
                    // Set the Mediation Provider.
                    HyprMX.setMediationProvider(
                        mediator = "Chartboost Mediation",
                        mediatorSDKVersion = HeliumSdk.getVersion(),
                        adapterVersion = adapterVersion,
                    )
                } ?: run {
                PartnerLogController.log(SETUP_FAILED, "Missing distributorID.")
                continuation.resumeWith(
                    Result.failure(
                        ChartboostMediationAdException(
                            ChartboostMediationError.CM_INITIALIZATION_FAILURE_INVALID_CREDENTIALS,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Notify the HyprMX SDK of the GDPR applicability and consent status.
     *
     * @param context The current [Context].
     * @param applies True if GDPR applies, false otherwise.
     * @param gdprConsentStatus The user's GDPR consent status.
     */
    override fun setGdpr(
        context: Context,
        applies: Boolean?,
        gdprConsentStatus: GdprConsentStatus,
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            },
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            },
        )

        when (gdprConsentStatus) {
            GdprConsentStatus.GDPR_CONSENT_GRANTED ->
                checkHyprMxInitStateAndRun {
                    setUserConsentTask(
                        context,
                        ConsentStatus.CONSENT_GIVEN,
                    )
                }

            GdprConsentStatus.GDPR_CONSENT_DENIED ->
                checkHyprMxInitStateAndRun {
                    setUserConsentTask(
                        context,
                        ConsentStatus.CONSENT_DECLINED,
                    )
                }

            GdprConsentStatus.GDPR_CONSENT_UNKNOWN ->
                checkHyprMxInitStateAndRun {
                    setUserConsentTask(
                        context,
                        ConsentStatus.CONSENT_STATUS_UNKNOWN,
                    )
                }
        }
    }

    /**
     * HyprMX needs a unique generated identifier that needs to be static across sessions.
     * This is passed on SDK initialization.
     * For more information see: [userId](https://documentation.hyprmx.com/android-sdk/#userid)
     *
     * @param context a context that will be passed to the SharedPreferences to set the user ID.
     */
    private fun getGamerId(context: Context) =
        context.getSharedPreferences(HYPRMX_PREFS_KEY, Context.MODE_PRIVATE)
            .let { sharedPreferences ->
                // return an already set gamer id, otherwise generate and store one.
                sharedPreferences.getString(HYPRMX_GAMER_ID_KEY, null) ?: run {
                    UUID.randomUUID().toString().also { gamerId ->
                        val prefsWriteSucceeded = sharedPreferences.edit().putString(HYPRMX_GAMER_ID_KEY, gamerId).commit()
                        PartnerLogController.log(
                            CUSTOM,
                            "Gamer ID ${
                                if (prefsWriteSucceeded) "was" else "was not"
                            } successfully stored.",
                        )
                    }
                }
            }

    /**
     * Store a HyprMX user's consent value and set it to HyprMX.
     * This is passed on SDK initialization.
     *
     * @param context a context that will be passed to the SharedPreferences to set the user consent.
     * @param consentStatus the consent status value to be stored.
     */
    private fun setUserConsentTask(
        context: Context,
        consentStatus: ConsentStatus,
    ) {
        val prefsWriteSucceeded =
            context.getSharedPreferences(HYPRMX_PREFS_KEY, Context.MODE_PRIVATE)
                .edit()
                .putInt(HYPRMX_USER_CONSENT_KEY, consentStatus.ordinal)
                .commit()

        PartnerLogController.log(
            CUSTOM,
            "User consent ${
                if (prefsWriteSucceeded) "was" else "was not"
            } successfully stored.",
        )

        if (prefsWriteSucceeded) {
            HyprMX.setConsentStatus(consentStatus)
        }
    }

    /**
     * Get a stored user's consent and return its HyprMX equivalent.
     * This is passed on SDK initialization.
     *
     * @param context a context that will be passed to the SharedPreferences to set the user consent.
     *
     * @return An already stored user consent.
     */
    private fun getUserConsent(context: Context) =
        getConsentStatus(
            context.getSharedPreferences(
                HYPRMX_PREFS_KEY,
                Context.MODE_PRIVATE,
            ).getInt(HYPRMX_USER_CONSENT_KEY, 0),
        )

    /**
     * Translate an integer value to a HyprMX user consent enum.
     *
     * @param consentValue an [Int] to be translated to a HyprMX consent.
     *
     * @return a [ConsentStatus] based on the integer passed in.
     */
    private fun getConsentStatus(consentValue: Int) =
        when (consentValue) {
            0 -> ConsentStatus.CONSENT_STATUS_UNKNOWN
            1 -> ConsentStatus.CONSENT_GIVEN
            2 -> ConsentStatus.CONSENT_DECLINED
            else -> ConsentStatus.CONSENT_STATUS_UNKNOWN
        }

    /**
     * Notify HyprMX of the CCPA compliance.
     *
     * @param context The current [Context].
     * @param hasGrantedCcpaConsent True if the user has granted CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy String.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGrantedCcpaConsent: Boolean,
        privacyString: String,
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) {
                CCPA_CONSENT_GRANTED
            } else {
                CCPA_CONSENT_DENIED
            },
        )

        when (hasGrantedCcpaConsent) {
            true -> checkHyprMxInitStateAndRun { setUserConsentTask(context, ConsentStatus.CONSENT_GIVEN) }
            false -> checkHyprMxInitStateAndRun { setUserConsentTask(context, ConsentStatus.CONSENT_DECLINED) }
        }
    }

    /**
     * Notify HyprMX of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(
        context: Context,
        isSubjectToCoppa: Boolean,
    ) {
        PartnerLogController.log(
            if (isSubjectToCoppa) {
                COPPA_SUBJECT
            } else {
                COPPA_NOT_SUBJECT
            },
        )

        // COPPA is always set to true on SDK initialization.
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest,
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return emptyMap()
    }

    /**
     * Attempt to load a HyprMX ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format.key) {
            AdFormat.BANNER.key, "adaptive_banner" -> loadBannerAd(context, request, partnerAdListener)
            AdFormat.INTERSTITIAL.key -> loadInterstitialAd(request, partnerAdListener)
            AdFormat.REWARDED.key -> loadRewardedAd(request, partnerAdListener)
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT))
            }
        }
    }

    /**
     * Attempt to show the currently loaded HyprMX ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the HyprMX ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        context: Context,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        return when (partnerAd.request.format.key) {
            AdFormat.BANNER.key, "adaptive_banner" -> {
                // Banner ads do not have a separate "show" mechanism.
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }

            AdFormat.INTERSTITIAL.key, AdFormat.REWARDED.key -> showFullscreenAd(partnerAd)
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_UNSUPPORTED_AD_FORMAT))
            }
        }
    }

    /**
     * Discard unnecessary HyprMX ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the HyprMX ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(INVALIDATE_STARTED)

        return when (partnerAd.request.format.key) {
            AdFormat.BANNER.key, "adaptive_banner" -> destroyBannerAd(partnerAd)
            AdFormat.INTERSTITIAL.key, AdFormat.REWARDED.key -> {
                // HyprMX does not have destroy methods for their fullscreen ads.
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }

            else -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    /**
     * Attempt to load a HyprMX banner ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            HyprMXBannerView(
                context = context,
                placementName = request.partnerPlacement,
                adSize = getHyprMXBannerAdSize(request.size),
            ).apply {
                listener =
                    object : HyprMXBannerListener {
                        override fun onAdClicked(ad: HyprMXBannerView) {
                            PartnerLogController.log(DID_CLICK)
                            partnerAdListener.onPartnerAdClicked(
                                PartnerAd(
                                    ad = ad,
                                    details = emptyMap(),
                                    request = request,
                                ),
                            )
                        }

                        override fun onAdClosed(ad: HyprMXBannerView) {}

                        override fun onAdFailedToLoad(
                            ad: HyprMXBannerView,
                            error: HyprMXErrors,
                        ) {
                            PartnerLogController.log(
                                LOAD_FAILED,
                                "Error: ${error.name}. " +
                                    "Ordinal: ${error.ordinal}",
                            )
                            continuation.resume(
                                Result.failure(
                                    ChartboostMediationAdException(
                                        getChartboostMediationError(error),
                                    ),
                                ),
                            )
                        }

                        override fun onAdLeftApplication(ad: HyprMXBannerView) {}

                        override fun onAdLoaded(ad: HyprMXBannerView) {
                            PartnerLogController.log(LOAD_SUCCEEDED)
                            continuation.resume(
                                Result.success(
                                    PartnerAd(
                                        ad = ad,
                                        details = emptyMap(),
                                        request = request,
                                    ),
                                ),
                            )
                        }

                        override fun onAdOpened(ad: HyprMXBannerView) {}
                    }
                loadAd()
            }
        }
    }

    /**
     * Find the most appropriate HyprMX ad size for the given screen area based on height.
     *
     * @param size The [Size] to parse for conversion.
     *
     * @return The HyprMX ad size that best matches the given [Size].
     */
    private fun getHyprMXBannerAdSize(size: Size?): HyprMXBannerSize {
        return size?.height?.let {
            when {
                it in 50 until 90 -> HyprMXBannerSize.HyprMXAdSizeBanner
                it in 90 until 250 -> HyprMXBannerSize.HyprMXAdSizeLeaderboard
                it >= 250 -> HyprMXBannerSize.HyprMXAdSizeMediumRectangle
                else -> HyprMXBannerSize.HyprMXAdSizeBanner
            }
        } ?: HyprMXBannerSize.HyprMXAdSizeBanner
    }

    /**
     * Attempt to load a HyprMX interstitial ad.
     *
     * @param request An [PartnerAdLoadRequest] instance containing data to load the ad with.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitialAd(
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            HyprMX.getPlacement(request.partnerPlacement).apply {
                setPlacementListener(
                    InterstitialAdListener(
                        continuationRef = WeakReference(continuation),
                        request = request,
                        listener = partnerAdListener
                    )
                )
                loadAd()
            }
        }
    }

    /**
     * Attempt to load a HyprMX rewarded ad.
     *
     * @param request The [PartnerAdLoadRequest] containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedAd(
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            HyprMX.getPlacement(request.partnerPlacement).apply {
                setPlacementListener(
                    RewardedAdListener(
                        continuationRef = WeakReference(continuation),
                        request = request,
                        listener = partnerAdListener,
                    ),
                )
                loadAd()
            }
        }
    }

    /**
     * Attempt to show a HyprMX fullscreen ad.
     *
     * @param partnerAd The [PartnerAd] object containing the HyprMX ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showFullscreenAd(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)
        return (partnerAd.ad as? Placement)?.let { placement ->
            suspendCancellableCoroutine { continuation ->
                val weakContinuationRef = WeakReference(continuation)

                fun resumeOnce(result: Result<PartnerAd>) {
                    weakContinuationRef.get()?.let {
                        if (it.isActive) {
                            it.resume(result)
                        }
                    } ?: run {
                        PartnerLogController.log(SHOW_FAILED, "Unable to resume continuation once. Continuation is null.")
                    }
                }

                onShowSuccess = {
                    PartnerLogController.log(SHOW_SUCCEEDED)
                    resumeOnce(
                        Result.success(partnerAd)
                    )
                }

                onShowError = { error ->
                    PartnerLogController.log(
                        SHOW_FAILED,
                        "Failed to show due to error: ${getChartboostMediationError(error)}",
                    )

                    resumeOnce(
                        Result.failure(
                            ChartboostMediationAdException(
                                getChartboostMediationError(error),
                            ),
                        ),
                    )
                }
                if (placement.isAdAvailable()) placement.showAd()
            }
        } ?: run {
            PartnerLogController.log(
                PartnerLogController.PartnerAdapterEvents.SHOW_FAILED,
                "Ad is not Placement.",
            )
            Result.failure(
                ChartboostMediationAdException(
                    ChartboostMediationError.CM_SHOW_FAILURE_WRONG_RESOURCE_TYPE,
                ),
            )
        }
    }

    /**
     * Destroy the current HyprMX banner ad.
     *
     * @param partnerAd The [PartnerAd] object containing the HyprMX ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyBannerAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return (partnerAd.ad as? HyprMXBannerView)?.let { bannerAd ->
            bannerAd.destroy()

            PartnerLogController.log(INVALIDATE_SUCCEEDED)
            Result.success(partnerAd)
        } ?: run {
            PartnerLogController.log(
                INVALIDATE_FAILED,
                "Ad is null or not an instance of HyprMXBannerView.",
            )
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Checks that the HyprMX initialization state has completed. If so, then run the function;
     * otherwise, do nothing.
     *
     * @param function the function that will be run after the initialization state check was successful.
     */
    private fun checkHyprMxInitStateAndRun(function: () -> Unit) {
        if (HyprMX.getInitializationState() != HyprMXState.INITIALIZATION_COMPLETE) {
            PartnerLogController.log(CUSTOM, "Cannot run $function. The HyprMX SDK has not initialized.")
            return
        }
        function()
    }

    /**
     * Callback class for interstitial ads.
     *
     * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
     * @param request A [PartnerAdLoadRequest] object containing the request.
     * @param listener A [PartnerAdListener] to be notified of ad events.
     */
    private class InterstitialAdListener(
        private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
        private val request: PartnerAdLoadRequest,
        private val listener: PartnerAdListener,
    ) : PlacementListener {
        fun resumeOnce(result: Result<PartnerAd>) {
            continuationRef.get()?.let {
                if (it.isActive) {
                    it.resume(result)
                }
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "Unable to resume continuation. Continuation is null.")
            }
        }

        override fun onAdStarted(placement: Placement?) {
            PartnerLogController.log(SHOW_SUCCEEDED)
            onShowSuccess()
            onShowSuccess = {}
        }

        override fun onAdClosed(
            placement: Placement?,
            finished: Boolean,
        ) {
            PartnerLogController.log(DID_DISMISS)
            listener.onPartnerAdDismissed(
                PartnerAd(
                    ad = placement,
                    details = emptyMap(),
                    request = request,
                ),
                null,
            )
        }

        override fun onAdDisplayError(
            placement: Placement?,
            hyprMXError: HyprMXErrors?,
        ) {
            PartnerLogController.log(SHOW_FAILED)
            hyprMXError?.let {
                onShowError(it)
                onShowError = {}
            }
        }

        override fun onAdAvailable(placement: Placement?) {
            PartnerLogController.log(LOAD_SUCCEEDED)
            resumeOnce(
                Result.success(
                    PartnerAd(
                        ad = placement,
                        details = emptyMap(),
                        request = request,
                    ),
                ),
            )
        }

        override fun onAdNotAvailable(placement: Placement?) {
            PartnerLogController.log(LOAD_FAILED)
            resumeOnce(
                Result.failure(
                    ChartboostMediationAdException(
                        ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL,
                    ),
                ),
            )
        }
    }

    /**
     * Callback class for rewarded ads.
     *
     * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
     * @param request A [PartnerAdLoadRequest] object containing the request.
     * @param listener A [PartnerAdListener] to be notified of ad events.
     */
    private class RewardedAdListener(
        private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
        private val request: PartnerAdLoadRequest,
        private val listener: PartnerAdListener,
    ) : RewardedPlacementListener {

        fun resumeOnce(result: Result<PartnerAd>) {
            continuationRef.get()?.let {
                if (it.isActive) {
                    it.resume(result)
                }
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "Unable to resume continuation. Continuation is null.")
            }
        }

        override fun onAdStarted(placement: Placement?) {
            PartnerLogController.log(SHOW_SUCCEEDED)
            onShowSuccess()
            onShowSuccess = {}
        }

        override fun onAdClosed(
            placement: Placement?,
            finished: Boolean,
        ) {
            PartnerLogController.log(DID_DISMISS)
            listener.onPartnerAdDismissed(
                PartnerAd(
                    ad = placement,
                    details = emptyMap(),
                    request = request,
                ),
                null,
            )
        }

        override fun onAdDisplayError(
            placement: Placement?,
            hyprMXError: HyprMXErrors?,
        ) {
            PartnerLogController.log(SHOW_FAILED)
            hyprMXError?.let {
                onShowError(it)
                onShowError = {}
            }
        }

        override fun onAdAvailable(placement: Placement?) {
            PartnerLogController.log(LOAD_SUCCEEDED)
            resumeOnce(
                Result.success(
                    PartnerAd(
                        ad = placement,
                        details = emptyMap(),
                        request = request,
                    ),
                ),
            )
        }

        override fun onAdNotAvailable(placement: Placement?) {
            PartnerLogController.log(LOAD_FAILED)
            resumeOnce(
                Result.failure(
                    ChartboostMediationAdException(
                        ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL,
                    ),
                ),
            )
        }

        override fun onAdRewarded(
            placement: Placement,
            rewardName: String,
            rewardValue: Int,
        ) {
            PartnerLogController.log(DID_REWARD)
            listener.onPartnerAdRewarded(
                PartnerAd(
                    ad = placement,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }
    }
}
