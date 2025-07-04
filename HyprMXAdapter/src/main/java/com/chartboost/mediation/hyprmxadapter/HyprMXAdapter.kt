/*
 * Copyright 2023-2025 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.hyprmxadapter

import android.app.Activity
import android.content.Context
import android.util.Size
import com.chartboost.chartboostmediationsdk.ChartboostMediationSdk
import com.chartboost.chartboostmediationsdk.ad.ChartboostMediationBannerAdView.ChartboostMediationBannerSize.Companion.asSize
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.CUSTOM
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_DISMISS
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_GRANTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_UNKNOWN
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_NOT_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USP_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USP_CONSENT_GRANTED
import com.chartboost.core.consent.*
import com.hyprmx.android.sdk.banner.HyprMXBannerListener
import com.hyprmx.android.sdk.banner.HyprMXBannerSize
import com.hyprmx.android.sdk.banner.HyprMXBannerView
import com.hyprmx.android.sdk.consent.ConsentStatus
import com.hyprmx.android.sdk.core.HyprMX
import com.hyprmx.android.sdk.core.HyprMXErrors
import com.hyprmx.android.sdk.core.HyprMXIf
import com.hyprmx.android.sdk.core.HyprMXState
import com.hyprmx.android.sdk.core.InitResult
import com.hyprmx.android.sdk.placement.HyprMXLoadAdListener
import com.hyprmx.android.sdk.placement.HyprMXRewardedShowListener
import com.hyprmx.android.sdk.placement.HyprMXShowListener
import com.hyprmx.android.sdk.placement.Placement
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Chartboost Mediation HyperMX SDK adapter.
 */
class HyprMXAdapter : PartnerAdapter {
    companion object {
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
         * Convert a given HyprMX error code into a [ChartboostMediationError].
         *
         * @param error The HyprMX error code as an [Int].
         *
         * @return The corresponding [ChartboostMediationError].
         */
        internal fun getChartboostMediationError(error: HyprMXErrors) =
            when (error) {
                HyprMXErrors.NO_FILL -> ChartboostMediationError.LoadError.NoFill
                HyprMXErrors.SDK_NOT_INITIALIZED -> ChartboostMediationError.InitializationError.Unknown
                HyprMXErrors.INVALID_BANNER_PLACEMENT_NAME, HyprMXErrors.PLACEMENT_DOES_NOT_EXIST,
                HyprMXErrors.PLACEMENT_NAME_NOT_SET,
                -> ChartboostMediationError.LoadError.InvalidPartnerPlacement

                HyprMXErrors.DISPLAY_ERROR, HyprMXErrors.AD_FAILED_TO_RENDER -> ChartboostMediationError.ShowError.MediaBroken
                HyprMXErrors.AD_SIZE_NOT_SET -> ChartboostMediationError.LoadError.InvalidBannerSize
                else -> ChartboostMediationError.OtherError.PartnerError
            }

        /**
         * A lambda to call for successful HyprMX ad shows.
         */
        internal var onShowSuccess: () -> Unit = {}

        /**
         * A lambda to call for failed HyprMX ad shows.
         */
        internal var onShowError: (hyprMXErrors: HyprMXErrors) -> Unit = { _: HyprMXErrors -> }

        /**
         * A list of fullscreen partner placements that have been loaded. Ad queuing is not supported
         * with HyprMX due to internal implementation constraints.
         */
        internal val loadedPartnerPlacements = mutableSetOf<String>()

        /**
         * Checks that the HyprMX initialization state has completed. If so, then run the function;
         * otherwise, do nothing.
         *
         * @param function the function that will be run after the initialization state check was successful.
         */
        private fun checkHyprMxInitStateAndRun(function: () -> Unit) {
            if (HyprMX.getInitializationState() != HyprMXState.INITIALIZATION_COMPLETE) {
                PartnerLogController.log(
                    CUSTOM,
                    "Cannot run $function. The HyprMX SDK has not initialized.",
                )
                return
            }
            function()
        }

        /**
         * Store a HyprMX user's consent value and set it to HyprMX.
         *
         * @param context a context that will be passed to the SharedPreferences to set the user consent.
         * @param consentStatus the consent status value to be stored.
         */
        internal fun setUserConsentTask(
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

            checkHyprMxInitStateAndRun {
                HyprMX.setConsentStatus(consentStatus)
            }
        }
    }

    /**
     * The HyperMX adapter configuration.
     */
    override var configuration: PartnerAdapterConfiguration = HyprMXAdapterConfiguration

    /**
     * A map of Chartboost Mediation's listeners for the corresponding load identifier.
     */
    private val listeners = mutableMapOf<String, PartnerAdListener>()

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
    ): Result<Map<String, Any>> {
        PartnerLogController.log(SETUP_STARTED)

        setConsents(context, partnerConfiguration.consents, partnerConfiguration.consents.keys)

        return suspendCoroutine { continuation ->
            Json.decodeFromJsonElement<String>(
                (partnerConfiguration.credentials as JsonObject).getValue(DISTRIBUTOR_ID_KEY),
            ).trim()
                .takeIf { it.isNotEmpty() }?.let { distributorId ->
                    HyprMX.initialize(
                        context = context,
                        distributorId = distributorId,
                        listener =
                        object : HyprMXIf.HyprMXInitializationListener {
                            override fun onInitialized(result: InitResult) {
                                if (result.success) {
                                    PartnerLogController.log(SETUP_SUCCEEDED)
                                    continuation.resume(
                                        Result.success(emptyMap())
                                    )
                                } else {
                                    PartnerLogController.log(SETUP_FAILED)
                                    continuation.resume(
                                        Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.Unknown))
                                    )
                                }
                            }
                        },
                    )
                    // Set the Mediation Provider.
                    HyprMX.setMediationProvider(
                        mediator = "Chartboost Mediation",
                        mediatorSDKVersion = ChartboostMediationSdk.getVersion(),
                        adapterVersion = configuration.adapterVersion,
                    )
                    HyprMX.setConsentStatus(getUserConsent(context))
                    HyprMX.setAgeRestrictedUser(partnerConfiguration.isUserUnderage ?: true)

                } ?: run {
                PartnerLogController.log(SETUP_FAILED, "Missing distributorID.")
                continuation.resumeWith(
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.InvalidCredentials))
                )
            }
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
     * Notify HyprMX of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isUserUnderage True if the user is subject to COPPA, false otherwise.
     */
    override fun setIsUserUnderage(
        context: Context,
        isUserUnderage: Boolean,
    ) {
        PartnerLogController.log(
            if (isUserUnderage) {
                USER_IS_UNDERAGE
            } else {
                USER_IS_NOT_UNDERAGE
            },
        )

        checkHyprMxInitStateAndRun {
            HyprMX.setAgeRestrictedUser(isUserUnderage)
        }
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdPreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PartnerAdPreBidRequest,
    ): Result<Map<String, String>> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return Result.success(emptyMap())
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

        if (loadedPartnerPlacements.contains(request.partnerPlacement)) {
            PartnerLogController.log(LOAD_FAILED)
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.Unknown))
        }

        return when (request.format) {
            PartnerAdFormats.BANNER -> loadBannerAd(context, request, partnerAdListener)
            PartnerAdFormats.INTERSTITIAL -> loadInterstitialAd(request, partnerAdListener)
            PartnerAdFormats.REWARDED -> loadRewardedAd(request, partnerAdListener)
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat))
            }
        }
    }

    /**
     * Attempt to show the currently loaded HyprMX ad.
     *
     * @param activity The current [Activity]
     * @param partnerAd The [PartnerAd] object containing the HyprMX ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        activity: Activity,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)
        val listener = listeners.remove(partnerAd.request.identifier)

        return when (partnerAd.request.format) {
            PartnerAdFormats.BANNER -> {
                // Banner ads do not have a separate "show" mechanism.
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }

            PartnerAdFormats.INTERSTITIAL, PartnerAdFormats.REWARDED ->
                showFullscreenAd(
                    partnerAd,
                    listener,
                )

            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.UnsupportedAdFormat))
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
        listeners.remove(partnerAd.request.identifier)

        return when (partnerAd.request.format) {
            PartnerAdFormats.BANNER -> destroyBannerAd(partnerAd)
            PartnerAdFormats.INTERSTITIAL, PartnerAdFormats.REWARDED -> {
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

    override fun setConsents(
        context: Context,
        consents: Map<ConsentKey, ConsentValue>,
        modifiedKeys: Set<ConsentKey>,
    ) {
        if (HyprMXAdapterConfiguration.isConsentStatusOverridden) {
            return
        }
        val consent = consents[configuration.partnerId]?.takeIf { it.isNotBlank() }
            ?: consents[ConsentKeys.GDPR_CONSENT_GIVEN]?.takeIf { it.isNotBlank() }
        consent?.let {
            if (it == ConsentValues.DOES_NOT_APPLY) {
                return@let
            }
            PartnerLogController.log(
                when (it) {
                    ConsentValues.GRANTED -> GDPR_CONSENT_GRANTED
                    ConsentValues.DENIED -> GDPR_CONSENT_DENIED
                    else -> GDPR_CONSENT_UNKNOWN
                },
            )

            when (it) {
                ConsentValues.GRANTED ->
                    setUserConsentTask(
                        context,
                        ConsentStatus.CONSENT_GIVEN,
                    )

                ConsentValues.DENIED ->
                    setUserConsentTask(
                        context,
                        ConsentStatus.CONSENT_DECLINED,
                    )

                else ->
                    setUserConsentTask(
                        context,
                        ConsentStatus.CONSENT_STATUS_UNKNOWN,
                    )
            }
            // If we set GDPR consent, we should not check USP
            return@setConsents
        }

        val hasGrantedUspConsent =
            consents[ConsentKeys.CCPA_OPT_IN]?.takeIf { it.isNotBlank() }
                ?.equals(ConsentValues.GRANTED)
                ?: consents[ConsentKeys.USP]?.takeIf { it.isNotBlank() }
                    ?.let { ConsentManagementPlatform.getUspConsentFromUspString(it) }
        hasGrantedUspConsent?.let {
            PartnerLogController.log(
                if (hasGrantedUspConsent) {
                    USP_CONSENT_GRANTED
                } else {
                    USP_CONSENT_DENIED
                },
            )

            when (hasGrantedUspConsent) {
                true ->
                    setUserConsentTask(
                        context,
                        ConsentStatus.CONSENT_GIVEN,
                    )

                false ->
                    setUserConsentTask(
                        context,
                        ConsentStatus.CONSENT_DECLINED,
                    )
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
            val banner = HyprMXBannerView(
                context = context,
                placementName = request.partnerPlacement,
                adSize = getHyprMXBannerAdSize(request.bannerSize?.asSize()),
            )
            banner.listener =
                object : HyprMXBannerListener {
                    override fun onAdClicked(view: HyprMXBannerView) {
                        PartnerLogController.log(PartnerLogController.PartnerAdapterEvents.DID_CLICK)
                        partnerAdListener.onPartnerAdClicked(
                            PartnerAd(
                                ad = view,
                                details = emptyMap(),
                                request = request,
                            ),
                        )
                    }

                    override fun onAdClosed(view: HyprMXBannerView) {}

                    override fun onAdOpened(view: HyprMXBannerView) {}

                    override fun onAdImpression(view: HyprMXBannerView) {}

                    @Deprecated("This callback will be removed on a future SDK release.\nUse the app's lifecycle or the activity's lifecycle events as alternative.")
                    override fun onAdLeftApplication(view: HyprMXBannerView) {
                    }
                }
            banner.loadAd(
                listener =
                object : HyprMXLoadAdListener {
                    override fun onAdLoaded(isAdAvailable: Boolean) {
                        if (isAdAvailable) {
                            PartnerLogController.log(LOAD_SUCCEEDED)
                            continuation.resume(
                                Result.success(
                                    PartnerAd(
                                        ad = banner,
                                        details = emptyMap(),
                                        request = request,
                                    ),
                                ),
                            )
                        } else {
                            PartnerLogController.log(LOAD_FAILED)
                            continuation.resume(
                                Result.failure(
                                    ChartboostMediationAdException(ChartboostMediationError.LoadError.NoFill)
                                ),
                            )
                        }
                    }
                }
            )
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
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.identifier] = listener
        loadedPartnerPlacements.add(request.partnerPlacement)

        return suspendCancellableCoroutine { continuation ->
            HyprMX.getPlacement(request.partnerPlacement).apply {
                loadAd(
                    LoadAdListener(
                        continuationRef = WeakReference(continuation),
                        request = request,
                        placement = this,
                    )
                )
            }
        }
    }

    /**
     * Attempt to load a HyprMX rewarded ad.
     *
     * @param request The [PartnerAdLoadRequest] containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedAd(
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.identifier] = listener
        loadedPartnerPlacements.add(request.partnerPlacement)

        return suspendCancellableCoroutine { continuation ->
            HyprMX.getPlacement(request.partnerPlacement).apply {
                loadAd(
                    LoadAdListener(
                        continuationRef = WeakReference(continuation),
                        request = request,
                        placement = this,
                    )
                )
            }
        }
    }

    /**
     * Attempt to show a HyprMX fullscreen ad.
     *
     * @param partnerAd The [PartnerAd] object containing the HyprMX ad to be shown.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showFullscreenAd(
        partnerAd: PartnerAd,
        listener: PartnerAdListener?,
    ): Result<PartnerAd> {
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
                        PartnerLogController.log(
                            SHOW_FAILED,
                            "Unable to resume continuation once. Continuation is null.",
                        )
                    }
                }

                onShowSuccess = {
                    PartnerLogController.log(SHOW_SUCCEEDED)
                    resumeOnce(
                        Result.success(partnerAd),
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

                if (placement.isAdAvailable()) {
                    placement.showAd(
                        FullscreenAdShowListener(
                            request = partnerAd.request,
                            listener = listener,
                        )
                    )
                }
            }
        } ?: run {
            PartnerLogController.log(
                PartnerLogController.PartnerAdapterEvents.SHOW_FAILED,
                "Ad is not Placement.",
            )
            Result.failure(
                ChartboostMediationAdException(
                    ChartboostMediationError.ShowError.WrongResourceType,
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
        loadedPartnerPlacements.remove(partnerAd.request.partnerPlacement)
        return (partnerAd.ad as? HyprMXBannerView)?.let { bannerAd ->
            bannerAd.destroy()

            PartnerLogController.log(INVALIDATE_SUCCEEDED)
            Result.success(partnerAd)
        } ?: run {
            PartnerLogController.log(
                INVALIDATE_FAILED,
                "Ad is null or not an instance of HyprMXBannerView.",
            )
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
        }
    }
}

/**
 * Callback class for load events.
 */
private class LoadAdListener(
    private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
    private val request: PartnerAdLoadRequest,
    private val placement: Placement,
) : HyprMXLoadAdListener {
    fun resumeOnce(result: Result<PartnerAd>) {
        continuationRef.get()?.let {
            if (it.isActive) {
                it.resume(result)
            }
        } ?: run {
            PartnerLogController.log(
                LOAD_FAILED,
                "Unable to resume continuation. Continuation is null."
            )
        }
    }

    override fun onAdLoaded(isAdAvailable: Boolean) {
        if (isAdAvailable) {
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
        } else {
            PartnerLogController.log(LOAD_FAILED)
            resumeOnce(
                Result.failure(
                    ChartboostMediationAdException(
                        ChartboostMediationError.LoadError.NoFill,
                    ),
                ),
            )
        }
    }
}

/**
 * Callback class for fullscreen ad show events.
 *
 * @param request The [PartnerAdLoadRequest] object containing the ad request data.
 * @param listener The [PartnerAdListener] to notify of ad events.
 */
private class FullscreenAdShowListener(
    private val request: PartnerAdLoadRequest,
    private val listener: PartnerAdListener?,
) : HyprMXShowListener, HyprMXRewardedShowListener {
    override fun onAdClosed(
        placement: Placement,
        finished: Boolean,
    ) {
        PartnerLogController.log(DID_DISMISS)
        HyprMXAdapter.loadedPartnerPlacements.remove(request.partnerPlacement)
        listener?.onPartnerAdDismissed(
            PartnerAd(
                ad = placement,
                details = emptyMap(),
                request = request,
            ),
            null,
        ) ?: run {
            PartnerLogController.log(
                CUSTOM,
                "Unable to notify partner ad dismissal. Listener is null.",
            )
        }
    }

    override fun onAdDisplayError(
        placement: Placement,
        hyprMXError: HyprMXErrors,
    ) {
        hyprMXError.let {
            HyprMXAdapter.onShowError(it)
            HyprMXAdapter.onShowError = {}
        }
    }

    override fun onAdImpression(placement: Placement) {
        PartnerLogController.log(PartnerLogController.PartnerAdapterEvents.DID_TRACK_IMPRESSION)
        listener?.onPartnerAdImpression(
            PartnerAd(
                ad = placement,
                details = emptyMap(),
                request = request,
            ),
        ) ?: run {
            PartnerLogController.log(
                CUSTOM,
                "Unable to notify partner ad impression. Listener is null.",
            )
        }
    }

    override fun onAdRewarded(
        placement: Placement,
        rewardName: String,
        rewardValue: Int,
    ) {
        PartnerLogController.log(PartnerLogController.PartnerAdapterEvents.DID_REWARD)
        listener?.onPartnerAdRewarded(
            PartnerAd(
                ad = placement,
                details = emptyMap(),
                request = request,
            ),
        ) ?: run {
            PartnerLogController.log(
                CUSTOM,
                "Unable to notify partner ad reward. Listener is null.",
            )
        }
    }

    override fun onAdStarted(placement: Placement) {
        HyprMXAdapter.onShowSuccess()
        HyprMXAdapter.onShowSuccess = {}
    }
}
